#!/usr/bin/env bash
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
suffix="$(openssl rand -hex 4)"
project="courtpulse-m10-${suffix}"
artifact_dir="${repository}/build/verification/milestone-10/${project}"
mkdir -p "${artifact_dir}"
date -u '+started_at_utc=%Y-%m-%dT%H:%M:%SZ' >"${artifact_dir}/timing.txt"

export COURTPULSE_COMPOSE_PROJECT="${project}"
export COURTPULSE_DB_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_POSTGRES_HOST_PORT=57433
export COURTPULSE_LOCALSTACK_HOST_PORT=56567
export COURTPULSE_API_HOST_PORT=60081
export COURTPULSE_WEB_HOST_PORT=56173
export COURTPULSE_IDP_HOST_PORT=59181
export COURTPULSE_MAILPIT_SMTP_HOST_PORT=11025
export COURTPULSE_MAILPIT_UI_HOST_PORT=18026
export COURTPULSE_MAILPIT_PORT=11025
export COURTPULSE_MAILPIT_URL='http://127.0.0.1:18026'
export COURTPULSE_IDP_ADMIN_USERNAME="m10-admin-${suffix}"
export COURTPULSE_IDP_ADMIN_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_USER_A="m10-user-a-${suffix}"
export COURTPULSE_TEST_USER_A_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_USER_B="m10-user-b-${suffix}"
export COURTPULSE_TEST_USER_B_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_OPS_USER="m10-ops-${suffix}"
export COURTPULSE_TEST_OPS_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_AUTH_ENABLED=true
export COURTPULSE_AUTH_ISSUER_BASE='http://127.0.0.1:59181'
export COURTPULSE_AUTH_ISSUER_URI='http://127.0.0.1:59181/realms/courtpulse'
export COURTPULSE_AUTH_ISSUER_ORIGIN='http://127.0.0.1:59181'
export COURTPULSE_AUTH_JWK_SET_URI='http://identity:8080/realms/courtpulse/protocol/openid-connect/certs'
export COURTPULSE_AUTH_AUDIENCE='courtpulse-api'
export COURTPULSE_AUTH_CLIENT_ID='courtpulse-web'
export COURTPULSE_AUTH_BROWSER_SCOPE='openid profile'
export COURTPULSE_AUTH_AUTHORITIES_CLAIM='courtpulse_roles'
export COURTPULSE_AUTH_AUTHORITY_PREFIX='ROLE_'
export COURTPULSE_AUTH_OPERATIONS_AUTHORITY='ROLE_courtpulse:ops'
export COURTPULSE_DB_URL='jdbc:postgresql://localhost:57433/courtpulse'
export COURTPULSE_DB_USERNAME=courtpulse
export COURTPULSE_SQS_ENDPOINT='http://localhost:56567'

compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml" \
  -p "${project}" --profile auth --profile delivery --profile reconciliation)

database() {
  "${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -At -F '|' -U courtpulse -d courtpulse "$@"
}

finish() {
  local status=$?
  date -u '+finished_at_utc=%Y-%m-%dT%H:%M:%SZ' >>"${artifact_dir}/timing.txt"
  "${compose[@]}" ps -a >"${artifact_dir}/compose-ps.txt" 2>&1 || true
  "${compose[@]}" logs --no-color >"${artifact_dir}/compose.log" 2>&1 || true
  for container in $("${compose[@]}" ps -q 2>/dev/null); do
    docker inspect --format '{{.Name}} restarts={{.RestartCount}} started={{.State.StartedAt}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
      "${container}" >>"${artifact_dir}/container-health.txt" 2>&1 || true
  done
  database -c 'SELECT rule_type,count(*) FROM alert_rules GROUP BY rule_type ORDER BY rule_type' \
    >"${artifact_dir}/rule-counts.txt" 2>&1 || true
  if command -v pmset >/dev/null 2>&1; then
    pmset -g log | grep -E "^$(date '+%Y-%m-%d') .*(Sleep +Entering Sleep state|DarkWake +DarkWake from|Wake +Wake from)" \
      >"${artifact_dir}/host-sleep-events.txt" || true
  fi
  database -c "SELECT game_id,status,generation,gap_sequence,last_error_code FROM game_reconciliations ORDER BY game_id" \
    >"${artifact_dir}/reconciliations.txt" 2>&1 || true
  database -c "SELECT game_id,state_version,last_sequence,home_score,away_score,state_checksum FROM game_checkpoints ORDER BY game_id" \
    >"${artifact_dir}/checkpoints.txt" 2>&1 || true
  "${compose[@]}" down -v --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  exit "${status}"
}
trap finish EXIT

cd "${repository}"
"${compose[@]}" up -d postgres localstack identity mailpit | tee "${artifact_dir}/infrastructure-up.txt"
for attempt in {1..120}; do
  if curl -fsS 'http://127.0.0.1:59181/realms/courtpulse/.well-known/openid-configuration' >/dev/null 2>&1 \
      && curl -fsS "${COURTPULSE_MAILPIT_URL}/api/v1/info" >/dev/null 2>&1; then break; fi
  if [[ "${attempt}" == 120 ]]; then echo 'Isolated identity or Mailpit did not become healthy' >&2; exit 1; fi
  sleep 1
done
"${repository}/scripts/provision-local-identity.sh" >"${artifact_dir}/identity-provisioned.txt"
"${repository}/gradlew" :apps:queue-replay-cli:run --args='--reset-import --inspect' --console=plain \
  >"${artifact_dir}/fixture-import.txt"
"${compose[@]}" up -d --build api web delivery-worker reconciliation-worker \
  | tee "${artifact_dir}/application-up.txt"
for attempt in {1..90}; do
  if curl -fsS 'http://127.0.0.1:60081/actuator/health/readiness' >/dev/null 2>&1 \
      && curl -fsS 'http://127.0.0.1:56173/healthz' >/dev/null 2>&1 \
      && [[ "$(docker inspect --format '{{.State.Health.Status}}' \
        "${project}-delivery-worker-1" 2>/dev/null || true)" == healthy ]] \
      && [[ "$(docker inspect --format '{{.State.Health.Status}}' \
        "${project}-reconciliation-worker-1" 2>/dev/null || true)" == healthy ]]; then break; fi
  if [[ "${attempt}" == 90 ]]; then echo 'Isolated application workers did not become healthy' >&2; exit 1; fi
  sleep 1
done
[[ "$(docker image inspect --format '{{.Config.User}}' "${project}-api")" == courtpulse ]]
[[ "$(docker image inspect --format '{{.Config.User}}' "${project}-web")" == 101 ]]
[[ "$(docker image inspect --format '{{.Config.User}}' "${project}-reconciliation-worker")" == courtpulse ]]

(
  cd "${repository}/apps/web"
  env PLAYWRIGHT_BASE_URL='http://127.0.0.1:56173' COURTPULSE_RULE_ACCEPTANCE=1 \
    COURTPULSE_DELIVERY_ACCEPTANCE=1 COURTPULSE_DELIVERY_AUTOMATIC=1 npx playwright test \
      e2e/rule-engine-acceptance.spec.ts --project=desktop-chromium --workers=1 --retries=0 2>&1
) | tee "${artifact_dir}/initial-browser.txt"
[[ "$(database -c "SELECT state_version FROM game_checkpoints WHERE game_id='game_synthetic_001'")" == 20 ]]
[[ "$(database -c "SELECT count(*) FROM delivery_attempts WHERE outcome='SENT'")" == 3 ]]

# The second test user owns a new threshold that the original history cannot reach.
# Their first alert and delivery will be caused only by the correction.
database -c "WITH owner AS (
    SELECT subject FROM application_users
    WHERE subject NOT IN (SELECT DISTINCT owner_subject FROM alert_rules WHERE owner_subject IS NOT NULL)
    LIMIT 1
  )
  INSERT INTO alert_rules(id,owner_subject,game_id,rule_type,enabled,player_id,
    points_threshold,client_request_id,request_fingerprint,version,created_at,updated_at)
  SELECT gen_random_uuid(),subject,'game_synthetic_001','PLAYER_POINTS',true,'player_home_2',
    6,'m10-correction-home2',repeat('a',64),1,now(),now() FROM owner" \
  >"${artifact_dir}/second-user-rule.txt"
[[ "$(database -c "SELECT count(*) FROM alert_rules WHERE client_request_id='m10-correction-home2'")" == 1 ]]
database -c "WITH owner AS (
    SELECT owner_subject AS subject FROM alert_rules WHERE client_request_id='m10-correction-home2'
  ) INSERT INTO notification_preferences(owner_subject,in_app_enabled,email_enabled,updated_at)
    SELECT subject,true,true,now() FROM owner" >/dev/null
database -c "WITH owner AS (
    SELECT owner_subject AS subject FROM alert_rules WHERE client_request_id='m10-correction-home2'
  ) INSERT INTO notification_destinations(id,owner_subject,channel,address,enabled,created_at,updated_at)
    SELECT gen_random_uuid(),subject,'EMAIL','new-correction@example.test',true,now(),now() FROM owner" >/dev/null

(
  cd "${repository}/apps/web"
  env PLAYWRIGHT_BASE_URL='http://127.0.0.1:56173' COURTPULSE_CORRECTION_ACCEPTANCE=1 \
    npx playwright test e2e/correction-acceptance.spec.ts \
      --project=desktop-chromium --workers=1 --retries=0
) | tee "${artifact_dir}/correction-browser.txt"
for attempt in {1..40}; do
  if [[ "$(database -c "SELECT count(*) FROM delivery_attempts WHERE outcome='SENT'")" == 4 ]]; then break; fi
  if [[ "${attempt}" == 40 ]]; then echo 'New correction email did not complete' >&2; exit 1; fi
  sleep 1
done
result="$(database -c "SELECT
  (SELECT count(*) FROM alert_instances WHERE owner_subject IS NOT NULL AND status='CORRECTED'),
  (SELECT count(*) FROM alert_instances WHERE owner_subject IS NOT NULL AND status='CREATED'
    AND rule_id=(SELECT id::text FROM alert_rules WHERE client_request_id='m10-correction-home2')),
  (SELECT count(*) FROM delivery_attempts WHERE outcome='SENT'),
  (SELECT count(*) FROM alert_deliveries WHERE channel='EMAIL' AND status='DELIVERED'),
  (SELECT count(*) FROM outbox WHERE event_type='RESYNC_REQUIRED'),
  checkpoint.state_version, checkpoint.player_points->>'player_ace',
  checkpoint.player_points->>'player_home_2'
  FROM game_checkpoints checkpoint WHERE game_id='game_synthetic_001'")"
printf '%s\n' "${result}" | tee "${artifact_dir}/correction-result.txt"
[[ "${result}" == '1|1|4|4|1|21|11|7' ]] || {
  echo "Unexpected corrected alerts/delivery/state: ${result}" >&2; exit 1;
}
[[ "$(database -c "SELECT count(*) FROM delivery_attempts attempt
  JOIN alert_deliveries delivery ON delivery.id=attempt.delivery_id
  JOIN alert_instances alert ON alert.id=delivery.alert_id
  WHERE alert.status='CORRECTED' AND attempt.outcome='SENT'")" == 1 ]]

"${repository}/gradlew" :apps:queue-replay-cli:run \
  --args="--correction-fixture=${repository}/fixtures/corrections/gap-initial.json" --console=plain \
  >"${artifact_dir}/gap-submitted.txt"
for attempt in {1..30}; do
  if [[ "$(database -c "SELECT status FROM game_reconciliations WHERE game_id='game_gap_001'")" == BLOCKED ]]; then break; fi
  if [[ "${attempt}" == 30 ]]; then echo 'Missing sequence did not block' >&2; exit 1; fi
  sleep 1
done
[[ "$(database -c "SELECT last_sequence FROM game_checkpoints WHERE game_id='game_gap_001'")" == 0 ]]
[[ "$(database -c "SELECT gap_sequence FROM game_reconciliations WHERE game_id='game_gap_001'")" == 2 ]]
"${repository}/gradlew" :apps:queue-replay-cli:run \
  --args="--correction-fixture=${repository}/fixtures/corrections/gap-resolution.json" --console=plain \
  >"${artifact_dir}/gap-resolution.txt"
for attempt in {1..30}; do
  if [[ "$(database -c "SELECT status FROM game_reconciliations WHERE game_id='game_gap_001'")" == COMPLETED ]]; then break; fi
  if [[ "${attempt}" == 30 ]]; then echo 'Gap did not resolve after new data' >&2; exit 1; fi
  sleep 1
done
[[ "$(database -c "SELECT last_sequence FROM game_checkpoints WHERE game_id='game_gap_001'")" == 3 ]]

generation_before="$(database -c "SELECT generation FROM game_reconciliations WHERE game_id='game_synthetic_001'")"
"${compose[@]}" restart reconciliation-worker >"${artifact_dir}/worker-restart.txt"
"${repository}/gradlew" :apps:queue-replay-cli:run \
  --args="--correction-fixture=${repository}/fixtures/corrections/ace-to-home-2.json" --console=plain \
  >"${artifact_dir}/duplicate-correction.txt"
sleep 3
[[ "$(database -c "SELECT generation FROM game_reconciliations WHERE game_id='game_synthetic_001'")" == "${generation_before}" ]]
[[ "$(database -c "SELECT count(*) FROM delivery_attempts WHERE outcome='SENT'")" == 4 ]]
echo 'Milestone 10 isolated correction, resync, alerts, gap recovery, and restart passed.'
