#!/usr/bin/env bash
# Isolated M12 proof: the production BALLDONTLIE adapter follows a fictional overtime game served
# by the local simulator through throttling, an outage, a provider correction, and restarts.
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
suffix="$(openssl rand -hex 4)"
project="courtpulse-m12-${suffix}"
artifact_dir="${repository}/build/verification/milestone-12/${project}"
mkdir -p "${artifact_dir}"
date -u '+started_at_utc=%Y-%m-%dT%H:%M:%SZ' >"${artifact_dir}/timing.txt"

for tool in docker curl jq openssl npx; do
  command -v "${tool}" >/dev/null || { echo "Required tool missing: ${tool}" >&2; exit 1; }
done

COURTPULSE_DB_PASSWORD="$(openssl rand -hex 18)"
COURTPULSE_SIMULATOR_API_KEY="m12-$(openssl rand -hex 16)"
export COURTPULSE_DB_PASSWORD COURTPULSE_SIMULATOR_API_KEY
export COURTPULSE_BALLDONTLIE_API_KEY="${COURTPULSE_SIMULATOR_API_KEY}"
export COURTPULSE_PROVIDER_BASE_URL='http://provider-simulator:8080'
export COURTPULSE_SIMULATOR_MODE=manual
export COURTPULSE_SIMULATOR_CORRECT_AFTER_ORDER=0
export COURTPULSE_INGEST_POLL_INTERVAL=2s
export COURTPULSE_INGEST_FINAL_REFETCH_INTERVAL=5s
export COURTPULSE_PROVIDER_REQUESTS_PER_MINUTE=600
export COURTPULSE_LIVE_FRESHNESS_WINDOW=20s
export COURTPULSE_POSTGRES_HOST_PORT=57633
export COURTPULSE_LOCALSTACK_HOST_PORT=56767
export COURTPULSE_API_HOST_PORT=60381
export COURTPULSE_WEB_HOST_PORT=56473
export COURTPULSE_SIMULATOR_HOST_PORT=18390

api="http://127.0.0.1:${COURTPULSE_API_HOST_PORT}"
simulator="http://127.0.0.1:${COURTPULSE_SIMULATOR_HOST_PORT}"
game_id='bdl-game-990001'
compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml"
  -p "${project}" --profile live --profile simulator --profile reconciliation)

database() {
  "${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -At -F '|' -U courtpulse -d courtpulse "$@"
}

simulate() {
  curl -fsS -X POST -H "Authorization: ${COURTPULSE_SIMULATOR_API_KEY}" "${simulator}/__simulator/$1" \
    >>"${artifact_dir}/simulator-actions.jsonl"
  echo >>"${artifact_dir}/simulator-actions.jsonl"
}

snapshot_field() {
  curl -fsS "${api}/api/v1/games/${game_id}" 2>/dev/null | jq -r "$1" 2>/dev/null || true
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

applied_through() { [[ "$(snapshot_field '.lastAppliedSequence')" == "$1" ]]; }
data_status_is() { [[ "$(snapshot_field '.dataStatus')" == "$1" ]]; }
scheduled_with_names() {
  [[ "$(snapshot_field '[.status, .homeTeamName, (.scheduledAt != null)] | join("|")')" \
    == 'SCHEDULED|Harbor City Herons|true' ]]
}
circuit_is() { [[ "$(database -c "SELECT circuit_state FROM provider_health WHERE source='balldontlie'")" == "$1" ]]; }
alert_status() {
  database -c "SELECT status FROM alert_instances WHERE rule_id=(SELECT id::text FROM alert_rules
    WHERE client_request_id='$1')"
}
corrected_state() {
  [[ "$(alert_status m12-bo)" == CORRECTED && "$(alert_status m12-ada)" == CREATED \
    && "$(snapshot_field '.playerPoints["bdl-player-9000101"]')" == 33 ]]
}
names_resolved() { [[ "$(snapshot_field '.playerNames["bdl-player-9000101"]')" == 'Ada Lane' ]]; }

finish() {
  local status=$?
  date -u '+finished_at_utc=%Y-%m-%dT%H:%M:%SZ' >>"${artifact_dir}/timing.txt"
  "${compose[@]}" ps -a >"${artifact_dir}/compose-ps.txt" 2>&1 || true
  "${compose[@]}" logs --no-color >"${artifact_dir}/compose.log" 2>&1 || true
  database -c "SELECT * FROM provider_health" >"${artifact_dir}/provider-health.txt" 2>&1 || true
  database -c "SELECT game_id, lifecycle, observed_plays, consecutive_failures, last_error_code
    FROM provider_game_observations" >"${artifact_dir}/provider-observations.txt" 2>&1 || true
  database -c "SELECT reason, detail, provider_event_id FROM provider_data_incidents" \
    >"${artifact_dir}/provider-incidents.txt" 2>&1 || true
  "${compose[@]}" down -v --rmi local --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  [[ ${status} -eq 0 ]] || echo "Milestone 12 verification failed; evidence is in ${artifact_dir}" >&2
  exit "${status}"
}
trap finish EXIT

cd "${repository}"
"${compose[@]}" build api web processor-worker ingestor-worker reconciliation-worker provider-simulator \
  >"${artifact_dir}/build.txt" 2>&1
"${compose[@]}" up -d postgres localstack provider-simulator >"${artifact_dir}/infrastructure-up.txt" 2>&1
wait_until 'PostgreSQL, LocalStack, and simulator healthy' 180 healthy postgres localstack provider-simulator
"${compose[@]}" up -d api web processor-worker ingestor-worker reconciliation-worker \
  >"${artifact_dir}/application-up.txt" 2>&1
wait_until 'API, web, and workers healthy' 180 healthy api web processor-worker ingestor-worker reconciliation-worker
[[ "$(docker image inspect --format '{{.Config.User}}' "${project}-provider-simulator")" == courtpulse ]]

# 1. Discovery shows the scheduled game with provider names before any play exists.
wait_until 'scheduled provider game discovered with team names' 60 scheduled_with_names
database >/dev/null <<SQL
INSERT INTO application_users(subject, created_at, last_seen_at)
VALUES ('m12-fan-${suffix}', now(), now());
INSERT INTO alert_rules(id, owner_subject, game_id, rule_type, enabled, player_id, points_threshold,
  client_request_id, request_fingerprint, version, created_at, updated_at) VALUES
  (gen_random_uuid(), 'm12-fan-${suffix}', '${game_id}', 'PLAYER_POINTS', true, 'bdl-player-9000102', 8,
   'm12-bo', repeat('d', 64), 1, now(), now()),
  (gen_random_uuid(), 'm12-fan-${suffix}', '${game_id}', 'PLAYER_POINTS', true, 'bdl-player-9000101', 31,
   'm12-ada', repeat('e', 64), 1, now(), now());
INSERT INTO alert_rules(id, owner_subject, game_id, rule_type, enabled, maximum_margin, eligible_period,
  maximum_clock_millis_remaining, client_request_id, request_fingerprint, version, created_at, updated_at)
VALUES (gen_random_uuid(), 'm12-fan-${suffix}', '${game_id}', 'CLOSE_GAME', true, 3, 5, 300000,
  'm12-ot-close', repeat('f', 64), 1, now(), now());
SQL

# 2. Plays released by the provider flow through the outbox, SQS, and processor.
simulate 'release?through=60'
wait_until 'first 60 provider plays applied' 60 applied_through 60
[[ "$(snapshot_field '.status + "|" + .dataStatus')" == 'LIVE|LIVE' ]]
curl -fsS "${api}/api/v1/games/${game_id}/events?limit=3" >"${artifact_dir}/events-page.json"
jq -e '.items[0].description | test("gains possession")' "${artifact_dir}/events-page.json" >/dev/null

# 3. Provider 429s are honored without losing data.
simulate 'rate-limit?count=4&retryAfter=1'
simulate 'release?through=100'
wait_until 'plays 61-100 applied despite 429 responses' 90 applied_through 100
[[ "$(database -c "SELECT rate_limited_total >= 4 FROM provider_health WHERE source='balldontlie'")" == t ]]

# 4. An outage is never shown as fresh data; the circuit opens and later recovers.
simulate 'outage?seconds=45'
wait_until 'dataStatus STALE during provider outage' 60 data_status_is STALE
wait_until 'provider circuit open during outage' 60 circuit_is OPEN
wait_until 'dataStatus LIVE after outage and circuit recovery' 150 data_status_is LIVE

# 5. Overtime and final.
simulate 'release?through=167'
wait_until 'overtime final applied' 90 applied_through 167
[[ "$(snapshot_field '[.status, .period, .homeScore, .awayScore] | map(tostring) | join("|")')" == 'FINAL|5|79|77' ]]
[[ "$(alert_status m12-bo)" == CREATED ]]
[[ "$(alert_status m12-ot-close)" == CREATED ]]
[[ -z "$(alert_status m12-ada)" ]]

# 6. The provider corrects an earlier scorer: one CORRECTED alert, one new alert, no duplicates.
simulate 'correct'
wait_until 'provider correction reconciled and alerts rewritten' 90 corrected_state
[[ "$(database -c "SELECT count(*) FROM canonical_events WHERE provider_event_id='990001:38'")" == 2 ]]
wait_until 'player display names resolved' 60 names_resolved
checksum="$(snapshot_field '.stateChecksum')"
alerts_before="$(database -c "SELECT count(*) FROM alert_instances")"

# 7. Restarted workers and a one-shot re-ingest change nothing.
"${compose[@]}" restart ingestor-worker processor-worker >"${artifact_dir}/restart.txt" 2>&1
wait_until 'workers healthy after restart' 90 healthy ingestor-worker processor-worker
sleep 6
"${compose[@]}" run --rm -T --no-deps ingestor-worker --ingest-game=990001 >"${artifact_dir}/ingest-game.txt"
grep -q 'new=0 corrections=0' "${artifact_dir}/ingest-game.txt"
[[ "$(snapshot_field '.stateChecksum')" == "${checksum}" ]]
[[ "$(database -c "SELECT count(*) FROM alert_instances")" == "${alerts_before}" ]]
[[ "$(database -c "SELECT count(*) FROM provider_data_incidents")" == 0 ]]
[[ "$(database -c "SELECT count(*) FROM alert_rules WHERE game_id='${game_id}' AND owner_subject IS NULL")" == 0 ]]

# 8. The browser shows names, overtime, play text, and the corrected total.
(
  cd "${repository}/apps/web"
  env PLAYWRIGHT_BASE_URL="http://127.0.0.1:${COURTPULSE_WEB_HOST_PORT}" COURTPULSE_LIVE_ACCEPTANCE=1 \
    npx playwright test e2e/live-provider-acceptance.spec.ts --project=desktop-chromium --workers=1 --retries=0
) >"${artifact_dir}/browser.txt" 2>&1 || { cat "${artifact_dir}/browser.txt" >&2; exit 1; }

printf 'final=%s checksum=%s alerts=%s\n' "$(snapshot_field '[.homeScore, .awayScore] | map(tostring) | join("-")')" \
  "${checksum}" "${alerts_before}" | tee -a "${artifact_dir}/summary.txt"
echo 'Milestone 12 live provider ingestion, outage, correction, overtime, and restart passed.' \
  | tee -a "${artifact_dir}/summary.txt"
