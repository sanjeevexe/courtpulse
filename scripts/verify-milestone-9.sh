#!/usr/bin/env bash
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project='courtpulse-m9-acceptance'
suffix="$(openssl rand -hex 4)"
artifact_dir="${repository}/build/verification/milestone-9/${suffix}"
export COURTPULSE_COMPOSE_PROJECT="${project}"

COURTPULSE_DB_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_DB_PASSWORD
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
COURTPULSE_IDP_ADMIN_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_IDP_ADMIN_PASSWORD
export COURTPULSE_TEST_USER_A="m9-user-a-${suffix}"
COURTPULSE_TEST_USER_A_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_USER_A_PASSWORD
export COURTPULSE_TEST_USER_B="m9-user-b-${suffix}"
COURTPULSE_TEST_USER_B_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_USER_B_PASSWORD
export COURTPULSE_TEST_OPS_USER="m9-ops-${suffix}"
COURTPULSE_TEST_OPS_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_OPS_PASSWORD
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
  "${compose[@]}" down -v --rmi local --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  exit "${status}"
}
trap finish EXIT

cd "${repository}"
# The sole cleanup target is the named, isolated M9 project.
"${compose[@]}" down -v --rmi local --remove-orphans >/dev/null 2>&1 || true
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
docker inspect --format '{{.State.Status}} finished={{.State.FinishedAt}}' \
  "${project}-delivery-worker-1" >"${artifact_dir}/worker-stopped-state.txt"
dlq_url="$("${compose[@]}" exec -T localstack awslocal sqs get-queue-url \
  --queue-name alert-deliveries-dlq.fifo --query QueueUrl --output text | tr -d '\r')"
queue_arn="$("${compose[@]}" exec -T localstack awslocal sqs get-queue-attributes \
  --queue-url "${queue_url}" --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text | tr -d '\r')"
dlq_arn="$("${compose[@]}" exec -T localstack awslocal sqs get-queue-attributes \
  --queue-url "${dlq_url}" --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text | tr -d '\r')"
printf 'source_url=%s\nsource_arn=%s\ndlq_url=%s\ndlq_arn=%s\n' \
  "${queue_url}" "${queue_arn}" "${dlq_url}" "${dlq_arn}" \
  >"${artifact_dir}/delivery-queue-identity.txt"
observe_delivery_queues() {
  local label="$1"
  local source_counts dlq_counts
  source_counts="$("${compose[@]}" exec -T localstack awslocal sqs get-queue-attributes \
    --queue-url "${queue_url}" --attribute-names ApproximateNumberOfMessages \
      ApproximateNumberOfMessagesNotVisible ApproximateNumberOfMessagesDelayed \
    --query Attributes --output json)"
  dlq_counts="$("${compose[@]}" exec -T localstack awslocal sqs get-queue-attributes \
    --queue-url "${dlq_url}" --attribute-names ApproximateNumberOfMessages \
      ApproximateNumberOfMessagesNotVisible ApproximateNumberOfMessagesDelayed \
    --query Attributes --output json)"
  jq -cn --arg at "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" --arg label "${label}" \
    --argjson source "${source_counts}" --argjson dlq "${dlq_counts}" \
    '{at:$at,label:$label,source:$source,dlq:$dlq}' \
    | tee -a "${artifact_dir}/delivery-queue-observations.jsonl"
}
delivery_attributes="{\"VisibilityTimeout\":\"2\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${dlq_arn}\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"
"${compose[@]}" exec -T localstack awslocal sqs set-queue-attributes \
  --queue-url "${queue_url}" --attributes "${delivery_attributes}"
"${compose[@]}" exec -T localstack awslocal sqs get-queue-attributes \
  --queue-url "${queue_url}" --attribute-names VisibilityTimeout RedrivePolicy \
  >"${artifact_dir}/delivery-queue-attributes.json"
[[ "$(jq -r '.Attributes.VisibilityTimeout' "${artifact_dir}/delivery-queue-attributes.json")" == 2 ]]
[[ "$(jq -r '.Attributes.RedrivePolicy | fromjson | .maxReceiveCount' \
  "${artifact_dir}/delivery-queue-attributes.json")" == 3 ]]
[[ "$(jq -r '.Attributes.RedrivePolicy | fromjson | .deadLetterTargetArn' \
  "${artifact_dir}/delivery-queue-attributes.json")" == "${dlq_arn}" ]]
before_poison="$(observe_delivery_queues 'before_poison_send')"
[[ "$(jq -r '.source.ApproximateNumberOfMessages' <<<"${before_poison}")" == 0 ]]
[[ "$(jq -r '.source.ApproximateNumberOfMessagesNotVisible' <<<"${before_poison}")" == 0 ]]
"${compose[@]}" exec -T localstack awslocal sqs send-message \
  --queue-url "${queue_url}" --message-body 'malformed-delivery-identity' \
  --message-group-id 'poison-delivery' --message-deduplication-id "poison-${suffix}" \
  >"${artifact_dir}/poison-send.txt"
poison_message_id="$(jq -r '.MessageId' "${artifact_dir}/poison-send.txt")"
printf 'message_id=%s\nmessage_group=poison-delivery\n' "${poison_message_id}" \
  >"${artifact_dir}/poison-identity.txt"
observe_delivery_queues 'after_poison_send' >/dev/null
deadline=$((SECONDS + 120))
attempt=0
highest_receive=0
dlq_count=0
while ((SECONDS < deadline)); do
  snapshot="$(observe_delivery_queues "before_poll_${attempt}")"
  source_visible="$(jq -r '.source.ApproximateNumberOfMessages // "0"' <<<"${snapshot}")"
  source_inflight="$(jq -r '.source.ApproximateNumberOfMessagesNotVisible // "0"' <<<"${snapshot}")"
  dlq_count="$(jq -r '.dlq.ApproximateNumberOfMessages // "0"' <<<"${snapshot}")"
  if [[ "${dlq_count}" == 1 && "${source_visible}" == 0 && "${source_inflight}" == 0 ]]; then
    break
  fi
  if [[ "${source_visible}" == 0 ]]; then
    sleep 2
    continue
  fi
  attempt=$((attempt + 1))
  LOGGING_LEVEL_COM_COURTPULSE_MESSAGING_DELIVERY=DEBUG \
    LOGGING_LEVEL_COM_COURTPULSE_MESSAGING_SQS=DEBUG \
    "${repository}/gradlew" :apps:queue-replay-cli:run --args='--delivery-drain' --console=plain \
    >"${artifact_dir}/poison-drain-${attempt}.txt"
  grep -Fq "SQS queue polled queueUrl=${queue_url} " \
    "${artifact_dir}/poison-drain-${attempt}.txt" || {
      echo 'Delivery CLI polled a different queue or did not poll' >&2; exit 1;
    }
  receive_count="$(grep -Eo "Delivery queue message received messageId=${poison_message_id} receiveCount=[0-9]+" \
    "${artifact_dir}/poison-drain-${attempt}.txt" | grep -Eo '[0-9]+$' | tail -1 || true)"
  if [[ -n "${receive_count}" ]]; then
    printf 'at=%s attempt=%s message_id=%s receive_count=%s\n' \
      "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "${attempt}" "${poison_message_id}" "${receive_count}" \
      >>"${artifact_dir}/poison-receives.txt"
    if ((receive_count <= highest_receive)); then
      echo "Poison receive count did not advance: ${receive_count}" >&2; exit 1
    fi
    highest_receive="${receive_count}"
  fi
  observe_delivery_queues "after_drain_${attempt}" >/dev/null
done
if [[ "${dlq_count}" != 1 || "${source_visible}" != 0 || "${source_inflight}" != 0 \
    || "${highest_receive}" -lt 3 ]]; then
  echo "Malformed delivery did not demonstrate redrive: source_visible=${source_visible}, source_inflight=${source_inflight}, dlq=${dlq_count}, highest_receive=${highest_receive}" >&2
  exit 1
fi
printf '%s\n' "${dlq_count}" >"${artifact_dir}/delivery-dlq-depth.txt"
[[ "$(database -c "SELECT count(*) FROM delivery_attempts")" == 3 ]]

echo 'Milestone 9 isolated local-email delivery, duplicate suppression, and DLQ passed.'
