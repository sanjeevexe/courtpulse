#!/usr/bin/env bash
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project="courtpulse-m7-acceptance"
artifact_dir="${repository}/build/verification/milestone-7"
suffix="$(openssl rand -hex 4)"

export COURTPULSE_COMPOSE_PROJECT="${project}"
export COURTPULSE_DB_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_POSTGRES_HOST_PORT=56432
export COURTPULSE_LOCALSTACK_HOST_PORT=55566
export COURTPULSE_API_HOST_PORT=59080
export COURTPULSE_WEB_HOST_PORT=55173
export COURTPULSE_IDP_HOST_PORT=58180
export COURTPULSE_IDP_ADMIN_USERNAME="m7-admin-${suffix}"
export COURTPULSE_IDP_ADMIN_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_USER_A="m7-user-a-${suffix}"
export COURTPULSE_TEST_USER_A_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_USER_B="m7-user-b-${suffix}"
export COURTPULSE_TEST_USER_B_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_TEST_OPS_USER="m7-ops-${suffix}"
export COURTPULSE_TEST_OPS_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_AUTH_ENABLED=true
export COURTPULSE_AUTH_ISSUER_BASE='http://127.0.0.1:58180'
export COURTPULSE_AUTH_ISSUER_URI='http://127.0.0.1:58180/realms/courtpulse'
export COURTPULSE_AUTH_ISSUER_ORIGIN='http://127.0.0.1:58180'
export COURTPULSE_AUTH_JWK_SET_URI='http://identity:8080/realms/courtpulse/protocol/openid-connect/certs'
export COURTPULSE_AUTH_AUDIENCE='courtpulse-api'
export COURTPULSE_AUTH_CLIENT_ID='courtpulse-web'
export COURTPULSE_AUTH_BROWSER_SCOPE='openid profile'
export COURTPULSE_AUTH_AUTHORITIES_CLAIM='courtpulse_roles'
export COURTPULSE_AUTH_AUTHORITY_PREFIX='ROLE_'
export COURTPULSE_AUTH_OPERATIONS_AUTHORITY='ROLE_courtpulse:ops'

mkdir -p "${artifact_dir}"
compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml" -p "${project}" --profile auth)

diagnostics() {
  "${compose[@]}" ps -a >"${artifact_dir}/compose-ps.txt" 2>&1 || true
  "${compose[@]}" logs --no-color >"${artifact_dir}/compose.log" 2>&1 || true
}

finish() {
  local status=$?
  diagnostics
  "${compose[@]}" down -v --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  exit "${status}"
}
trap finish EXIT

"${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${compose[@]}" up -d --build postgres localstack identity \
  | tee "${artifact_dir}/infrastructure-up.txt"

for attempt in {1..120}; do
  if curl -fsS 'http://127.0.0.1:58180/realms/courtpulse/.well-known/openid-configuration' >/dev/null; then
    break
  fi
  if [[ "${attempt}" == 120 ]]; then
    echo 'Timed out waiting for the isolated identity provider' >&2
    exit 1
  fi
  sleep 1
done

"${repository}/scripts/provision-local-identity.sh" >"${artifact_dir}/identity-provisioned.txt"

env \
  COURTPULSE_DB_URL='jdbc:postgresql://localhost:56432/courtpulse' \
  COURTPULSE_DB_USERNAME=courtpulse \
  COURTPULSE_SQS_ENDPOINT='http://localhost:55566' \
  "${repository}/gradlew" :apps:queue-replay-cli:run --args='--reset-import --run' \
  >"${artifact_dir}/seed.txt"

env COURTPULSE_AUTH_ENABLED=false COURTPULSE_AUTH_ISSUER_ORIGIN= \
  "${compose[@]}" up -d --build api web | tee "${artifact_dir}/anonymous-application-up.txt"
for attempt in {1..90}; do
  anonymous_config="$(curl -fsS 'http://127.0.0.1:55173/api/v1/auth/config' 2>/dev/null || true)"
  if [[ -n "${anonymous_config}" ]] \
      && [[ "$(jq -r '.enabled' <<<"${anonymous_config}" 2>/dev/null || true)" == false ]]; then
    break
  fi
  if [[ "${attempt}" == 90 ]]; then
    echo 'Timed out waiting for the authentication-disabled application' >&2
    exit 1
  fi
  sleep 1
done

for attempt in {1..90}; do
  pending_realtime="$("${compose[@]}" exec -T postgres psql -At -U courtpulse -d courtpulse -c \
    "SELECT count(*) FROM outbox WHERE destination='FUTURE_NOTIFICATIONS' AND status <> 'SENT'")"
  if [[ "${pending_realtime}" == 0 ]]; then
    break
  fi
  if [[ "${attempt}" == 90 ]]; then
    echo "Timed out waiting for realtime outbox publication; remaining=${pending_realtime}" >&2
    exit 1
  fi
  sleep 1
done

(
  cd "${repository}/apps/web"
  env PLAYWRIGHT_BASE_URL='http://127.0.0.1:55173' \
    npx playwright test e2e/dashboard.spec.ts e2e/auth-ownership.spec.ts \
      --workers=2 --retries=0
) | tee "${artifact_dir}/anonymous-browser.txt"

"${compose[@]}" stop web api >/dev/null
"${compose[@]}" up -d --force-recreate api web | tee "${artifact_dir}/application-up.txt"
for attempt in {1..90}; do
  config="$(curl -fsS 'http://127.0.0.1:55173/api/v1/auth/config' 2>/dev/null || true)"
  if [[ "$(jq -r '.enabled // false' <<<"${config}" 2>/dev/null || true)" == true ]]; then
    break
  fi
  if [[ "${attempt}" == 90 ]]; then
    echo 'Timed out waiting for the OIDC-enabled application' >&2
    exit 1
  fi
  sleep 1
done

config_keys="$(jq -r 'keys | sort | join(",")' <<<"${config}")"
[[ "${config_keys}" == 'clientId,enabled,issuer,scope' ]] || {
  echo "Public authentication configuration exposed unexpected fields: ${config_keys}" >&2
  exit 1
}

html_headers="$(curl -fsSI 'http://127.0.0.1:55173/')"
grep -Fqi "connect-src 'self' http://127.0.0.1:58180" <<<"${html_headers}"
grep -Fqi 'X-Content-Type-Options: nosniff' <<<"${html_headers}"
grep -Fqi 'X-Frame-Options: DENY' <<<"${html_headers}"
grep -Fqi 'Referrer-Policy: strict-origin-when-cross-origin' <<<"${html_headers}"
if grep -Eqi "connect-src[^;]*(\\*|'unsafe-inline'|'unsafe-eval')" <<<"${html_headers}"; then
  echo 'Unsafe Content-Security-Policy source detected' >&2
  exit 1
fi

asset_path="$(curl -fsS 'http://127.0.0.1:55173/' | sed -n 's#.*src="\(/assets/[^"]*\.js\)".*#\1#p' | head -1)"
[[ -n "${asset_path}" ]]
asset_headers="$(curl -fsSI "http://127.0.0.1:55173${asset_path}")"
grep -Fqi "connect-src 'self' http://127.0.0.1:58180" <<<"${asset_headers}"

web_image="${project}-web"
[[ "$(docker image inspect --format '{{.Config.User}}' "${web_image}")" == '101' ]]
docker run --rm --entrypoint sh "${web_image}" -c \
  'test ! -e /workspace && test -f /etc/nginx/templates/default.conf.template && test -f /usr/share/nginx/html/index.html'
docker history --no-trunc "${web_image}" >"${artifact_dir}/web-image-history.txt"
if docker run --rm -e COURTPULSE_AUTH_ISSUER_ORIGIN='https://identity.example/path' "${web_image}" \
    >"${artifact_dir}/malformed-csp-origin.txt" 2>&1; then
  echo 'Malformed browser issuer origin unexpectedly started Nginx' >&2
  exit 1
fi

(
  cd "${repository}/apps/web"
  env PLAYWRIGHT_BASE_URL='http://127.0.0.1:55173' COURTPULSE_AUTH_ACCEPTANCE=1 \
    COURTPULSE_TEST_USER_A="${COURTPULSE_TEST_USER_A}" \
    COURTPULSE_TEST_USER_A_PASSWORD="${COURTPULSE_TEST_USER_A_PASSWORD}" \
    COURTPULSE_TEST_USER_B="${COURTPULSE_TEST_USER_B}" \
    COURTPULSE_TEST_USER_B_PASSWORD="${COURTPULSE_TEST_USER_B_PASSWORD}" \
    COURTPULSE_TEST_OPS_USER="${COURTPULSE_TEST_OPS_USER}" \
    COURTPULSE_TEST_OPS_PASSWORD="${COURTPULSE_TEST_OPS_PASSWORD}" \
    npx playwright test e2e/auth-ownership.spec.ts --project=desktop-chromium --workers=1 --retries=0
) | tee "${artifact_dir}/browser.txt"

ownership="$("${compose[@]}" exec -T postgres psql -At -F '|' -U courtpulse -d courtpulse -c \
  "SELECT (SELECT count(*) FROM application_users), (SELECT count(*) FROM followed_games)")"
printf '%s\n' "${ownership}" | tee "${artifact_dir}/ownership-counts.txt"
[[ "${ownership}" == '3|1' ]] || {
  echo "Unexpected ownership counts: ${ownership}" >&2
  exit 1
}

echo 'Milestone 7 isolated OIDC and two-user ownership acceptance passed.'
