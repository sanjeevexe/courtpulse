#!/usr/bin/env bash
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project='courtpulse-m8-acceptance'
artifact_dir="${repository}/build/verification/milestone-8"
suffix="$(openssl rand -hex 4)"

export COURTPULSE_COMPOSE_PROJECT="${project}"
export COURTPULSE_DB_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_POSTGRES_HOST_PORT=57432
export COURTPULSE_LOCALSTACK_HOST_PORT=56566
export COURTPULSE_API_HOST_PORT=60080
export COURTPULSE_WEB_HOST_PORT=56173
export COURTPULSE_IDP_HOST_PORT=59180
export COURTPULSE_IDP_ADMIN_USERNAME="m8-admin-${suffix}"
export COURTPULSE_IDP_ADMIN_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_USER_A="m8-user-a-${suffix}"
export COURTPULSE_TEST_USER_A_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_USER_B="m8-user-b-${suffix}"
export COURTPULSE_TEST_USER_B_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_OPS_USER="m8-ops-${suffix}"
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
compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml" -p "${project}" --profile auth)

database() {
  "${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -At -F '|' -U courtpulse -d courtpulse "$@"
}

diagnostics() {
  "${compose[@]}" ps -a >"${artifact_dir}/compose-ps.txt" 2>&1 || true
  "${compose[@]}" logs --no-color >"${artifact_dir}/compose.log" 2>&1 || true
  database -c 'SELECT game_id,state_version,home_score,away_score,state_checksum FROM game_checkpoints' \
    >"${artifact_dir}/checkpoint.txt" 2>&1 || true
  database -c 'SELECT rule_type,owner_subject IS NULL AS system_rule,count(*) FROM alert_rules GROUP BY 1,2 ORDER BY 1,2' \
    >"${artifact_dir}/rule-counts.txt" 2>&1 || true
}

finish() {
  local status=$?
  diagnostics
  "${compose[@]}" down -v --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  exit "${status}"
}
trap finish EXIT

cd "${repository}"
# The only destructive cleanup target is this fixed, isolated acceptance project.
"${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${compose[@]}" up -d postgres localstack identity | tee "${artifact_dir}/infrastructure-up.txt"

for attempt in {1..120}; do
  if curl -fsS 'http://127.0.0.1:59180/realms/courtpulse/.well-known/openid-configuration' >/dev/null 2>&1; then
    break
  fi
  if [[ "${attempt}" == 120 ]]; then
    echo 'Timed out waiting for isolated OIDC discovery' >&2
    exit 1
  fi
  sleep 1
done
"${repository}/scripts/provision-local-identity.sh" >"${artifact_dir}/identity-provisioned.txt"

"${repository}/gradlew" :apps:queue-replay-cli:run --args='--reset-import --inspect' --console=plain \
  >"${artifact_dir}/fixture-import.txt"
before="$(database -c "SELECT (SELECT count(*) FROM canonical_events),
  (SELECT count(*) FROM processed_events),
  (SELECT count(*) FROM alert_rules WHERE owner_subject IS NULL),
  (SELECT state_version FROM game_checkpoints WHERE game_id='game_synthetic_001')")"
[[ "${before}" == '20|0|1|0' ]] || {
  echo "Fixture was not imported before processing: ${before}" >&2
  exit 1
}

"${compose[@]}" up -d --build api web | tee "${artifact_dir}/application-up.txt"
for attempt in {1..90}; do
  config="$(curl -fsS 'http://127.0.0.1:56173/api/v1/auth/config' 2>/dev/null || true)"
  if [[ "$(jq -r '.enabled // false' <<<"${config}" 2>/dev/null || true)" == true ]] \
      && curl -fsS 'http://127.0.0.1:60080/actuator/health/readiness' >/dev/null 2>&1 \
      && curl -fsS 'http://127.0.0.1:56173/healthz' >/dev/null 2>&1; then
    break
  fi
  if [[ "${attempt}" == 90 ]]; then
    echo 'Timed out waiting for auth-enabled API and web health' >&2
    exit 1
  fi
  sleep 1
done

[[ "$(docker image inspect --format '{{.Config.User}}' "${project}-api")" == courtpulse ]]
[[ "$(docker image inspect --format '{{.Config.User}}' "${project}-web")" == 101 ]]
docker run --rm --entrypoint sh "${project}-api" -c \
  'test -f /app/courtpulse-api.jar && test ! -e /workspace'
docker run --rm --entrypoint sh "${project}-web" -c \
  'test -f /usr/share/nginx/html/index.html && test ! -e /workspace'

(
  cd "${repository}/apps/web"
  env PLAYWRIGHT_BASE_URL='http://127.0.0.1:56173' COURTPULSE_RULE_ACCEPTANCE=1 \
    npx playwright test e2e/rule-engine-acceptance.spec.ts \
      --project=desktop-chromium --workers=1 --retries=0
) | tee "${artifact_dir}/rule-engine-browser.txt"

durable="$(database -c "SELECT
    (SELECT count(*) FROM processed_events),
    checkpoint.state_version,
    (SELECT count(*) FROM alert_instances WHERE owner_subject IS NOT NULL),
    (SELECT count(*) FROM alert_instances WHERE owner_subject IS NULL),
    (SELECT count(*) FROM alert_rules WHERE owner_subject IS NOT NULL),
    checkpoint.home_score, checkpoint.away_score,
    checkpoint.player_points ->> 'player_ace',
    checkpoint.state_checksum,
    (SELECT count(*) FROM outbox WHERE destination='FUTURE_NOTIFICATIONS' AND event_type='ALERT_CREATED'),
    (SELECT count(*) FROM (
      SELECT rule_id,trigger_key FROM alert_instances GROUP BY rule_id,trigger_key HAVING count(*) > 1
    ) duplicated)
  FROM game_checkpoints checkpoint WHERE game_id='game_synthetic_001'")"
expected='20|20|3|1|3|18|14|13|06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca|1|0'
printf '%s\n' "${durable}" | tee "${artifact_dir}/durable-result.txt"
[[ "${durable}" == "${expected}" ]] || {
  echo "Unexpected durable result: ${durable}" >&2
  exit 1
}

private_outbox_leaks="$(database -c "SELECT count(*)
  FROM outbox outbound JOIN alert_instances private
    ON private.owner_subject IS NOT NULL
   AND outbound.payload::text LIKE '%' || private.trigger_key || '%'
  WHERE outbound.destination='FUTURE_NOTIFICATIONS'")"
[[ "${private_outbox_leaks}" == 0 ]] || {
  echo "Private trigger identity leaked into public outbox: ${private_outbox_leaks}" >&2
  exit 1
}

(
  cd "${repository}/apps/web"
  env PLAYWRIGHT_BASE_URL='http://127.0.0.1:56173' COURTPULSE_AUTH_ACCEPTANCE=1 \
    npx playwright test e2e/dashboard.spec.ts e2e/auth-ownership.spec.ts \
      e2e/realtime-acceptance.spec.ts --project=desktop-chromium --workers=1 --retries=0
) | tee "${artifact_dir}/existing-browser.txt"

echo 'Milestone 8 isolated two-user rule, queue redelivery, and public privacy acceptance passed.'
