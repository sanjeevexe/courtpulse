#!/usr/bin/env bash
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project='courtpulse-m9-acceptance'
artifact_dir="${repository}/build/verification/milestone-9"
suffix="$(openssl rand -hex 4)"
export COURTPULSE_COMPOSE_PROJECT="${project}"

export COURTPULSE_DB_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_POSTGRES_HOST_PORT=57432
export COURTPULSE_LOCALSTACK_HOST_PORT=56566
export COURTPULSE_API_HOST_PORT=60080
export COURTPULSE_WEB_HOST_PORT=56173
export COURTPULSE_IDP_HOST_PORT=59180
export COURTPULSE_MAILPIT_SMTP_HOST_PORT=11025
export COURTPULSE_MAILPIT_UI_HOST_PORT=18025
export COURTPULSE_MAILPIT_PORT=11025
export COURTPULSE_MAILPIT_URL='http://127.0.0.1:18025'
export COURTPULSE_IDP_ADMIN_USERNAME="m9-admin-${suffix}"
export COURTPULSE_IDP_ADMIN_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_USER_A="m9-user-a-${suffix}"
export COURTPULSE_TEST_USER_A_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_USER_B="m9-user-b-${suffix}"
export COURTPULSE_TEST_USER_B_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_OPS_USER="m9-ops-${suffix}"
export COURTPULSE_TEST_OPS_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_AUTH_ENABLED=true
export COURTPULSE_AUTH_ISSUER_BASE='http://127.0.0.1:59180'
export COURTPULSE_AUTH_ISSUER_URI='http://127.0.0.1:59180/realms/courtpulse'
export COURTPULSE_AUTH_ISSUER_ORIGIN='http://127.0.0.1:59180'
export COURTPULSE_AUTH_JWK_SET_URI='http://identity:8080/realms/courtpulse/protocol/openid-connect/certs'
export COURTPULSE_AUTH_AUDIENCE='courtpulse-api'
export COURTPULSE_AUTH_CLIENT_ID='courtpulse-web'
export COURTPULSE_AUTH_BROWSER_SCOPE='openid profile'
export COURTPULSE_AUTH_AUTHORITIES_CLAIM='courtpulse_roles'
export COURTPULSE_AUTH_AUTHORITY_PREFIX='ROLE_'
export COURTPULSE_AUTH_OPERATIONS_AUTHORITY='ROLE_courtpulse:ops'
export COURTPULSE_DB_URL='jdbc:postgresql://localhost:57432/courtpulse'
export COURTPULSE_DB_USERNAME=courtpulse
export COURTPULSE_SQS_ENDPOINT='http://localhost:56566'

mkdir -p "${artifact_dir}"
compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml" \
  -p "${project}" --profile auth --profile delivery)

database() {
  "${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -At -F '|' -U courtpulse -d courtpulse "$@"
}

finish() {
  local status=$?
  "${compose[@]}" ps -a >"${artifact_dir}/compose-ps.txt" 2>&1 || true
  "${compose[@]}" logs --no-color >"${artifact_dir}/compose.log" 2>&1 || true
  "${compose[@]}" down -v --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  exit "${status}"
}
trap finish EXIT

cd "${repository}"
# The sole cleanup target is the named, isolated M9 project.
"${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${compose[@]}" up -d postgres localstack identity mailpit | tee "${artifact_dir}/infrastructure-up.txt"
for attempt in {1..120}; do
  if curl -fsS 'http://127.0.0.1:59180/realms/courtpulse/.well-known/openid-configuration' >/dev/null 2>&1 \
      && curl -fsS "${COURTPULSE_MAILPIT_URL}/api/v1/info" >/dev/null 2>&1; then break; fi
  if [[ "${attempt}" == 120 ]]; then echo 'Isolated identity or Mailpit did not become healthy' >&2; exit 1; fi
  sleep 1
done
"${repository}/scripts/provision-local-identity.sh" >"${artifact_dir}/identity-provisioned.txt"
"${repository}/gradlew" :apps:queue-replay-cli:run --args='--reset-import --inspect' --console=plain \
  >"${artifact_dir}/fixture-import.txt"
"${compose[@]}" up -d --build api web delivery-worker | tee "${artifact_dir}/application-up.txt"
for attempt in {1..90}; do
  if curl -fsS 'http://127.0.0.1:60080/actuator/health/readiness' >/dev/null 2>&1 \
      && curl -fsS 'http://127.0.0.1:56173/healthz' >/dev/null 2>&1 \
      && [[ "$(docker inspect --format '{{.State.Health.Status}}' \
        "${project}-delivery-worker-1" 2>/dev/null || true)" == healthy ]]; then break; fi
  if [[ "${attempt}" == 90 ]]; then echo 'Isolated API or web did not become healthy' >&2; exit 1; fi
  sleep 1
done
[[ "$(docker image inspect --format '{{.Config.User}}' "${project}-api")" == courtpulse ]]
[[ "$(docker image inspect --format '{{.Config.User}}' "${project}-web")" == 101 ]]
[[ "$(docker image inspect --format '{{.Config.User}}' "${project}-delivery-worker")" == courtpulse ]]

(
  cd "${repository}/apps/web"
  env PLAYWRIGHT_BASE_URL='http://127.0.0.1:56173' COURTPULSE_RULE_ACCEPTANCE=1 \
    COURTPULSE_DELIVERY_ACCEPTANCE=1 COURTPULSE_DELIVERY_AUTOMATIC=1 npx playwright test \
      e2e/rule-engine-acceptance.spec.ts --project=desktop-chromium --workers=1 --retries=0
) | tee "${artifact_dir}/delivery-browser.txt"

durable="$(database -c "SELECT
  (SELECT count(*) FROM processed_events), checkpoint.state_version,
  (SELECT count(*) FROM alert_deliveries WHERE channel='IN_APP' AND status='DELIVERED'),
  (SELECT count(*) FROM alert_deliveries WHERE channel='EMAIL' AND status='DELIVERED'),
  (SELECT count(*) FROM delivery_attempts WHERE outcome='SENT'),
  (SELECT count(*) FROM delivery_outbox WHERE status='SENT'),
  checkpoint.home_score, checkpoint.away_score, checkpoint.state_checksum
  FROM game_checkpoints checkpoint WHERE game_id='game_synthetic_001'")"
expected='20|20|3|3|3|3|18|14|06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca'
printf '%s\n' "${durable}" | tee "${artifact_dir}/durable-result.txt"
[[ "${durable}" == "${expected}" ]] || { echo "Unexpected M9 state: ${durable}" >&2; exit 1; }

(
  cd "${repository}/apps/web"
  env PLAYWRIGHT_BASE_URL='http://127.0.0.1:56173' COURTPULSE_AUTH_ACCEPTANCE=1 \
    npx playwright test e2e/dashboard.spec.ts e2e/auth-ownership.spec.ts \
      e2e/realtime-acceptance.spec.ts --project=desktop-chromium --workers=1 --retries=0
) | tee "${artifact_dir}/existing-browser.txt"

# Duplicate delivery messages are acknowledged without a second SMTP attempt.
delivery_id="$(database -c "SELECT id FROM alert_deliveries WHERE channel='EMAIL' ORDER BY id LIMIT 1")"
queue_url="$("${compose[@]}" exec -T localstack awslocal sqs get-queue-url \
  --queue-name alert-deliveries.fifo --query QueueUrl --output text | tr -d '\r')"
"${compose[@]}" exec -T localstack awslocal sqs send-message \
  --queue-url "${queue_url}" --message-body "${delivery_id}" \
  --message-group-id "${delivery_id}" --message-deduplication-id "duplicate-${suffix}" \
  >"${artifact_dir}/duplicate-send.txt"
"${repository}/gradlew" :apps:queue-replay-cli:run --args='--delivery-drain' --console=plain \
  >"${artifact_dir}/duplicate-drain.txt"
[[ "$(database -c "SELECT count(*) FROM delivery_attempts")" == 3 ]]

# A malformed payload is retried by SQS and isolated in the dedicated DLQ. A
# short visibility window is safe here because this is an isolated acceptance queue.
# The automatic worker has already delivered three messages; pause it so one
# deterministic consumer owns each receive during the poison-path assertion.
"${compose[@]}" stop delivery-worker >"${artifact_dir}/worker-paused-for-dlq.txt"
dlq_url="$("${compose[@]}" exec -T localstack awslocal sqs get-queue-url \
  --queue-name alert-deliveries-dlq.fifo --query QueueUrl --output text | tr -d '\r')"
dlq_arn="$("${compose[@]}" exec -T localstack awslocal sqs get-queue-attributes \
  --queue-url "${dlq_url}" --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text | tr -d '\r')"
delivery_attributes="{\"VisibilityTimeout\":\"2\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${dlq_arn}\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"
"${compose[@]}" exec -T localstack awslocal sqs set-queue-attributes \
  --queue-url "${queue_url}" --attributes "${delivery_attributes}"
"${compose[@]}" exec -T localstack awslocal sqs get-queue-attributes \
  --queue-url "${queue_url}" --attribute-names VisibilityTimeout RedrivePolicy \
  >"${artifact_dir}/delivery-queue-attributes.json"
"${compose[@]}" exec -T localstack awslocal sqs send-message \
  --queue-url "${queue_url}" --message-body 'malformed-delivery-identity' \
  --message-group-id 'poison-delivery' --message-deduplication-id "poison-${suffix}" \
  >"${artifact_dir}/poison-send.txt"
for attempt in {1..5}; do
  "${repository}/gradlew" :apps:queue-replay-cli:run --args='--delivery-drain' --console=plain \
    >"${artifact_dir}/poison-drain-${attempt}.txt"
  dlq_count="$("${compose[@]}" exec -T localstack awslocal sqs get-queue-attributes \
    --queue-url "${dlq_url}" --attribute-names ApproximateNumberOfMessages \
    --query 'Attributes.ApproximateNumberOfMessages' --output text | tr -d '\r')"
  if [[ "${dlq_count}" == 1 ]]; then break; fi
  if [[ "${attempt}" == 5 ]]; then echo 'Malformed delivery did not reach DLQ' >&2; exit 1; fi
  sleep 2
done
printf '%s\n' "${dlq_count}" >"${artifact_dir}/delivery-dlq-depth.txt"
[[ "$(database -c "SELECT count(*) FROM delivery_attempts")" == 3 ]]

echo 'Milestone 9 isolated local-email delivery, duplicate suppression, and DLQ passed.'
