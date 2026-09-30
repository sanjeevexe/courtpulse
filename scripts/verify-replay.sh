#!/usr/bin/env bash
# Real-game replay acceptance in an isolated Compose project. Imports the fictional fixture (and,
# with REAL_DATA=1, the 2026 playoffs from the public nba_data project), replays games at high
# speed through the real ingestion, queue, and processing path, and proves every replay reaches
# FINAL with exactly the recorded final score, every play applied, and no rejected plays.
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
suffix="$(openssl rand -hex 4)"
project="courtpulse-replay-${suffix}"
artifact_dir="${repository}/build/verification/replay/${project}"
mkdir -p "${artifact_dir}"

COURTPULSE_DB_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_DB_PASSWORD
export COURTPULSE_POSTGRES_HOST_PORT=58233
export COURTPULSE_LOCALSTACK_HOST_PORT=57367
export COURTPULSE_API_HOST_PORT=60981
export COURTPULSE_WEB_HOST_PORT=57073
api="http://127.0.0.1:${COURTPULSE_API_HOST_PORT}"
fixtures="${repository}/modules/testkit/src/main/resources/fixtures/providers/nba"

compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml"
  -p "${project}" --profile live --profile replay)

database() {
  "${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -At -F '|' -U courtpulse -d courtpulse "$@"
}

worker() {
  "${compose[@]}" run --rm -T --no-deps -v "${fixtures}:/fixtures:ro" processor-worker "$@"
}

finish() {
  local status=$?
  "${compose[@]}" logs --no-color >"${artifact_dir}/compose.log" 2>&1 || true
  "${compose[@]}" down -v --rmi local --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  [[ ${status} -eq 0 ]] || echo "Replay acceptance failed; evidence is in ${artifact_dir}" >&2
  exit "${status}"
}
trap finish EXIT

# Replays one catalog game at 120x and checks the result against the recorded final.
replay_and_verify() {
  local nba_game="$1" expected game_id state
  expected="$(database -c "SELECT home_score || '|' || away_score || '|' || action_count
    FROM replay_catalog WHERE nba_game_id = '${nba_game}'")"
  game_id="$(worker --replay-start="${nba_game}" --replay-speed=120 | sed -n 's/^Replay started: gameId=\([^ ]*\).*/\1/p')"
  [[ -n "${game_id}" ]] || { echo "Replay of ${nba_game} did not start" >&2; return 1; }
  for _ in $(seq 1 240); do
    state="$(curl -fsS "${api}/api/v1/games/${game_id}" 2>/dev/null | jq -r '.status' || true)"
    [[ "${state}" == FINAL ]] && break
    sleep 1
  done
  local actual
  actual="$(database -c "SELECT home_score || '|' || away_score || '|' || last_sequence
    FROM game_checkpoints WHERE game_id = '${game_id}'")"
  local incidents
  incidents="$(database -c "SELECT count(*) FROM provider_data_incidents WHERE game_id = '${game_id}'")"
  echo "${nba_game} ${game_id}: status=${state} expected(home|away|plays)=${expected} actual=${actual} incidents=${incidents}" \
    | tee -a "${artifact_dir}/results.txt"
  [[ "${state}" == FINAL && "${actual}" == "${expected}" && "${incidents}" == 0 ]]
}

cd "${repository}"
"${compose[@]}" build api processor-worker replay-worker >"${artifact_dir}/build.txt" 2>&1
"${compose[@]}" up -d --wait postgres localstack >"${artifact_dir}/infrastructure-up.txt" 2>&1
worker --migrate >"${artifact_dir}/migrate.txt"
worker --replay-import=/fixtures/fictional-playoff-game.csv | tee "${artifact_dir}/import-fixture.txt"
"${compose[@]}" up -d --wait api processor-worker replay-worker >"${artifact_dir}/services-up.txt" 2>&1

replay_and_verify 0049900101
replay_and_verify 0049900101 # a second run of the same game is a separate, equally correct game
names="$(curl -fsS "${api}/api/v1/games/nba-replay-0049900101-1" | jq -r '.playerNames["nba-player-101"] // "missing"')"
echo "player name resolved from the replay provider: ${names}" | tee -a "${artifact_dir}/results.txt"
[[ "${names}" == "A. Lane" ]]

if [[ "${REAL_DATA:-0}" == 1 ]]; then
  worker --replay-import=cdnnba_po_2025 | tee "${artifact_dir}/import-real.txt"
  # The two overtime games plus the most recent game in the dataset.
  for game in $(database -c "(SELECT nba_game_id FROM replay_catalog WHERE periods > 4 AND dataset = 'cdnnba_po_2025')
      UNION (SELECT nba_game_id FROM replay_catalog WHERE dataset = 'cdnnba_po_2025'
             ORDER BY started_at DESC LIMIT 1)"); do
    replay_and_verify "${game}"
  done
fi

echo "Replay acceptance passed: $(wc -l <"${artifact_dir}/results.txt" | tr -d ' ') checks in ${artifact_dir}/results.txt"
