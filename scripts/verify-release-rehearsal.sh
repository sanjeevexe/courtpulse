#!/usr/bin/env bash
# Rehearses the AWS release sequence locally and for free: one-off migrate task, services, canary,
# SES email (LocalStack), CloudFront-style WebSocket origin rules, and an API rollback to the
# previous release against the newer schema (expand-and-contract). Nothing touches AWS.
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
suffix="$(openssl rand -hex 4)"
project="courtpulse-release-${suffix}"
artifact_dir="${repository}/build/verification/release-rehearsal/${project}"
rollback_tree="${artifact_dir}/previous-release"
rollback_image="courtpulse-rollback-api:${suffix}"
mkdir -p "${artifact_dir}"
date -u '+started_at_utc=%Y-%m-%dT%H:%M:%SZ' >"${artifact_dir}/timing.txt"

for tool in docker curl jq openssl git; do
  command -v "${tool}" >/dev/null || { echo "Required tool missing: ${tool}" >&2; exit 1; }
done

COURTPULSE_DB_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_DB_PASSWORD
export COURTPULSE_EMAIL_PROVIDER=ses
export COURTPULSE_SES_ENDPOINT='http://localstack:4566'
export COURTPULSE_EMAIL_FROM='alerts@courtpulse.test'
export COURTPULSE_PUBLIC_BASE_URL='https://courtpulse.example.test'
export COURTPULSE_REALTIME_ALLOWED_ORIGINS="${COURTPULSE_PUBLIC_BASE_URL}"
export COURTPULSE_POSTGRES_HOST_PORT=57733
export COURTPULSE_LOCALSTACK_HOST_PORT=56867
export COURTPULSE_API_HOST_PORT=60481
export COURTPULSE_WEB_HOST_PORT=56573
export COURTPULSE_MAILPIT_SMTP_HOST_PORT=11325
export COURTPULSE_MAILPIT_UI_HOST_PORT=18325
rollback_port=60482

api="http://127.0.0.1:${COURTPULSE_API_HOST_PORT}"
localstack="http://127.0.0.1:${COURTPULSE_LOCALSTACK_HOST_PORT}"
compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml"
  -p "${project}" --profile live --profile delivery)

database() {
  "${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -At -F '|' -U courtpulse -d courtpulse "$@"
}

one_off() {
  "${compose[@]}" run --rm -T --no-deps processor-worker "$@"
}

wait_until() {
  local description="$1" seconds="$2"
  shift 2
  local started=${SECONDS}
  until "$@"; do
    if (( SECONDS - started >= seconds )); then
      echo "Timed out after ${seconds}s waiting for ${description}" >&2
      return 1
    fi
    sleep 1
  done
  echo "${description}: $(( SECONDS - started ))s" | tee -a "${artifact_dir}/timing.txt"
}

healthy() {
  local service
  for service in "$@"; do
    [[ "$(docker inspect --format '{{.State.Health.Status}}' "${project}-${service}-1" 2>/dev/null || true)" == healthy ]] \
      || return 1
  done
}

ready() { curl -fsS "$1/actuator/health/readiness" >/dev/null 2>&1; }
email_sent() { [[ "$(database -c "SELECT count(*) FROM delivery_attempts WHERE outcome='SENT'")" == 1 ]]; }

handshake_status() {
  curl -s --http1.1 --max-time 2 -o /dev/null -D - \
    -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
    -H 'Sec-WebSocket-Key: Y291cnRwdWxzZS1yZWhlYXJzYWw=' -H "Origin: $1" \
    "${api}/ws/v1/games" 2>/dev/null | awk 'NR == 1 {print $2}' || true
}

finish() {
  local status=$?
  date -u '+finished_at_utc=%Y-%m-%dT%H:%M:%SZ' >>"${artifact_dir}/timing.txt"
  docker rm -f "${project}-rollback-api" >/dev/null 2>&1 || true
  docker image rm -f "${rollback_image}" >/dev/null 2>&1 || true
  git -C "${repository}" worktree remove --force "${rollback_tree}" >/dev/null 2>&1 || true
  "${compose[@]}" ps -a >"${artifact_dir}/compose-ps.txt" 2>&1 || true
  "${compose[@]}" logs --no-color >"${artifact_dir}/compose.log" 2>&1 || true
  "${compose[@]}" down -v --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  [[ ${status} -eq 0 ]] || echo "Release rehearsal failed; evidence is in ${artifact_dir}" >&2
  exit "${status}"
}
trap finish EXIT

cd "${repository}"
"${compose[@]}" build api processor-worker delivery-worker >"${artifact_dir}/build.txt" 2>&1
"${compose[@]}" up -d postgres localstack mailpit >"${artifact_dir}/infrastructure-up.txt" 2>&1
wait_until 'PostgreSQL and LocalStack healthy' 180 healthy postgres localstack mailpit

# 1. The one-off migrate task runs before any service, exactly as CI does in ECS.
one_off --migrate >"${artifact_dir}/migrate.txt"
latest_migration="$(find modules/persistence/src/main/resources/db/migration -name 'V*__*.sql' \
  | sed -E 's|.*/V([0-9]+)__.*|\1|' | sort -n | tail -1)"
grep -q "Schema is at Flyway version ${latest_migration}$" "${artifact_dir}/migrate.txt"
"${compose[@]}" exec -T localstack awslocal ses verify-email-identity \
  --email-address "${COURTPULSE_EMAIL_FROM}" >/dev/null

# 2. A private rule with an email destination, so the release path sends a real SES email.
one_off --reset-import --inspect >"${artifact_dir}/fixture-import.txt"
database >/dev/null <<SQL
INSERT INTO application_users(subject, created_at, last_seen_at) VALUES ('release-fan-${suffix}', now(), now());
INSERT INTO alert_rules(id, owner_subject, game_id, rule_type, enabled, player_id, points_threshold,
  client_request_id, request_fingerprint, version, created_at, updated_at)
VALUES (gen_random_uuid(), 'release-fan-${suffix}', 'game_synthetic_001', 'PLAYER_POINTS', true,
  'player_ace', 12, 'release-ace-12', repeat('a', 64), 1, now(), now());
INSERT INTO notification_preferences(owner_subject, in_app_enabled, email_enabled, updated_at)
VALUES ('release-fan-${suffix}', true, true, now());
INSERT INTO notification_destinations(id, owner_subject, channel, address, enabled, created_at, updated_at)
VALUES (gen_random_uuid(), 'release-fan-${suffix}', 'EMAIL', 'fan-${suffix}@example.test', true, now(), now());
SQL

# 3. Services roll forward, then the canary proves outbox -> SQS -> processor -> checkpoint.
"${compose[@]}" up -d api processor-worker delivery-worker >"${artifact_dir}/services-up.txt" 2>&1
wait_until 'API, processor, and delivery services healthy' 180 healthy api processor-worker delivery-worker
one_off --canary >"${artifact_dir}/canary.txt"
grep -q 'Canary passed: game_synthetic_001 sequence=20 checksum=06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca' \
  "${artifact_dir}/canary.txt"

# 4. The delivery worker sends through the SES API with the configured sender and site link.
wait_until 'private alert delivered through SES' 90 email_sent
curl -fsS "${localstack}/_aws/ses?email=${COURTPULSE_EMAIL_FROM}" >"${artifact_dir}/ses-messages.json"
jq -e --arg to "fan-${suffix}@example.test" --arg link "${COURTPULSE_PUBLIC_BASE_URL}/games/game_synthetic_001" \
  '.messages | length == 1 and (.[0].Destination.ToAddresses | index($to)) != null
    and (.[0].Body.text_part | contains($link)) and (.[0].Body.text_part | contains("Mailpit") | not)' \
  "${artifact_dir}/ses-messages.json" >/dev/null
[[ "$(database -c "SELECT count(*) FROM delivery_attempts WHERE outcome='SENT' AND provider_message_id IS NOT NULL")" == 1 ]]

# 5. Only the configured public site origin may open the realtime socket.
[[ "$(handshake_status "${COURTPULSE_PUBLIC_BASE_URL}")" == 101 ]]
[[ "$(handshake_status 'https://attacker.example')" == 403 ]]

# 6. Roll the API back to the release before the newest migration; it must still serve the data.
newest_migration_commit="$(git log -1 --format=%H -- modules/persistence/src/main/resources/db/migration)"
previous_release="$(git rev-parse "${newest_migration_commit}^")"
echo "rollback_ref=${previous_release}" | tee -a "${artifact_dir}/summary.txt"
git worktree add --detach "${rollback_tree}" "${previous_release}" >/dev/null 2>&1
docker build -q -f "${rollback_tree}/apps/api/Dockerfile" -t "${rollback_image}" "${rollback_tree}" \
  >"${artifact_dir}/rollback-build.txt" 2>&1
"${compose[@]}" stop api >/dev/null 2>&1
docker run -d --name "${project}-rollback-api" --network "${project}_default" -p "127.0.0.1:${rollback_port}:8080" \
  -e COURTPULSE_DB_URL=jdbc:postgresql://postgres:5432/courtpulse -e COURTPULSE_DB_USERNAME=courtpulse \
  -e COURTPULSE_DB_PASSWORD="${COURTPULSE_DB_PASSWORD}" "${rollback_image}" >/dev/null
wait_until 'previous API release ready on the newer schema' 120 ready "http://127.0.0.1:${rollback_port}"
curl -fsS "http://127.0.0.1:${rollback_port}/api/v1/games/game_synthetic_001" >"${artifact_dir}/rollback-snapshot.json"
jq -e '.homeScore == 18 and .awayScore == 14 and .stateVersion >= 20' "${artifact_dir}/rollback-snapshot.json" >/dev/null
[[ "$(database -c "SELECT max(version::int) FROM flyway_schema_history WHERE success")" == "${latest_migration}" ]]

# 7. Roll forward again.
docker logs "${project}-rollback-api" >"${artifact_dir}/rollback-api.log" 2>&1
docker rm -f "${project}-rollback-api" >/dev/null
"${compose[@]}" start api >/dev/null 2>&1
wait_until 'current API ready after roll-forward' 120 ready "${api}"

echo 'Release rehearsal passed: migrate, canary, SES delivery, origin policy, rollback, and roll-forward.' \
  | tee -a "${artifact_dir}/summary.txt"
