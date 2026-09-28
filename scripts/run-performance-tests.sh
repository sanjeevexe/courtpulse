#!/usr/bin/env bash
# Reproducible local performance run in an isolated Compose project. Every number comes from real
# processing (PostgreSQL timestamps) or k6 against the running API; the report records the host,
# Docker resources, and commit so results are never presented without their environment.
#   1. Sustained ingest: GAMES x EVENTS at the plan's 200 events/s burst rate (fixed-rate rounds).
#   2. Saturation: the same volume committed all at once, to find the drain rate.
#   3. Hot games: 10 games with the maximum 1,000 owned rules each (10,000 rules) processed together.
#   4. Read API: k6 open-model arrival rate against the public REST API.
#   5. Realtime: CLIENTS WebSocket subscribers while a paced game produces hints.
set -Eeuo pipefail

# A sleeping laptop suspends the Docker VM and turns wall-clock latencies into nonsense.
if command -v caffeinate >/dev/null 2>&1 && [[ -z "${COURTPULSE_CAFFEINATED:-}" ]]; then
  COURTPULSE_CAFFEINATED=1 exec caffeinate -i "$0" "$@"
fi

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
suffix="$(openssl rand -hex 4)"
project="courtpulse-perf-${suffix}"
artifact_dir="${repository}/build/verification/performance/${project}"
report="${artifact_dir}/report.md"
mkdir -p "${artifact_dir}"
chmod 777 "${artifact_dir}" # k6 writes summaries as a non-root user

GAMES="${GAMES:-50}"
EVENTS="${EVENTS:-40}"
INGEST_RATE="${INGEST_RATE:-200}"   # events per second for the sustained workload
PEAK_RATE="${PEAK_RATE:-200}"
CLIENTS="${CLIENTS:-2000}"
k6_image='grafana/k6:2.3.0'

COURTPULSE_DB_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_DB_PASSWORD
export COURTPULSE_RATE_LIMIT_ENABLED=false    # a load generator is one client by construction
export COURTPULSE_REALTIME_SESSION_LIMIT=$(( CLIENTS + 500 ))
export COURTPULSE_CONSUMER_WORKERS=4
export COURTPULSE_POSTGRES_HOST_PORT=57933
export COURTPULSE_LOCALSTACK_HOST_PORT=57067
export COURTPULSE_API_HOST_PORT=60681
export COURTPULSE_WEB_HOST_PORT=56773

compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml"
  -p "${project}" --profile live)

database() {
  "${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -At -F '|' -U courtpulse -d courtpulse "$@"
}

k6() {
  docker run --rm --network "${project}_default" -v "${repository}/load/k6:/scripts:ro" \
    -v "${artifact_dir}:/results" "$@"
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
}

processed() {
  [[ "$(database -c "SELECT count(*) FROM processed_events p JOIN canonical_events c USING (event_id)
    WHERE c.game_id LIKE '$1-%'")" -ge "$2" ]]
}

latency_row() {
  database -c "WITH e AS (
      SELECT c.created_at, p.processed_at FROM canonical_events c
      JOIN processed_events p USING (event_id) WHERE c.game_id LIKE '$1-%')
    SELECT count(*),
      round(extract(epoch FROM max(processed_at) - min(created_at))::numeric, 2),
      round((count(*) / greatest(extract(epoch FROM max(processed_at) - min(created_at)), 0.001))::numeric, 1),
      round((percentile_cont(0.50) WITHIN GROUP (ORDER BY extract(epoch FROM processed_at - created_at)) * 1000)::numeric),
      round((percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM processed_at - created_at)) * 1000)::numeric),
      round((percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM processed_at - created_at)) * 1000)::numeric),
      round((max(extract(epoch FROM processed_at - created_at)) * 1000)::numeric)
    FROM e"
}

# Fails the run when the wall-clock span of an import is far longer than the importer's monotonic
# elapsed time, which only happens when the host or Docker VM was suspended mid-measurement.
assert_no_suspension() {
  local prefix="$1" import_file="$2" monotonic wall
  monotonic="$(sed -n 's/.*elapsedMillis=\([0-9]*\).*/\1/p' "${import_file}")"
  wall="$(database -c "SELECT round(extract(epoch FROM max(created_at) - min(created_at)) * 1000)
    FROM canonical_events WHERE game_id LIKE '${prefix}-%'")"
  if (( wall > monotonic + 5000 )); then
    echo "Clock jump during '${prefix}' (wall ${wall} ms vs monotonic ${monotonic} ms): host suspended; rerun" >&2
    return 1
  fi
}

k6_metric() { jq -r "$2 // \"n/a\"" "${artifact_dir}/$1" 2>/dev/null | awk '{ if ($1 ~ /^[0-9.]+$/) printf "%.0f", $1; else print $1 }'; }

finish() {
  local status=$?
  "${compose[@]}" logs --no-color >"${artifact_dir}/compose.log" 2>&1 || true
  docker rm -f "${project}-k6-ws" >/dev/null 2>&1 || true
  "${compose[@]}" down -v --rmi local --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  [[ ${status} -eq 0 ]] || echo "Performance run failed; evidence is in ${artifact_dir}" >&2
  exit "${status}"
}
trap finish EXIT

cd "${repository}"
cpu="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs)"
docker_resources="$(docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes')"
{
  echo "# CourtPulse performance run"
  echo
  echo "- Date (UTC): $(date -u '+%Y-%m-%d %H:%M')"
  echo "- Commit: \`$(git rev-parse --short HEAD)\`$(git diff --quiet || echo ' (with uncommitted changes)')"
  echo "- Host CPU: ${cpu}; Docker VM: ${docker_resources}"
  echo "- Topology: one API container, one processor container (4 consumer threads), PostgreSQL 17,"
  echo "  LocalStack SQS FIFO, all on one Docker host; k6 ${k6_image#*:} on the same network."
  echo "- Rate limiting disabled for the run (a load generator is a single client)."
  echo
} >"${report}"

"${compose[@]}" build api processor-worker >"${artifact_dir}/build.txt" 2>&1
"${compose[@]}" up -d --wait postgres localstack >"${artifact_dir}/infrastructure-up.txt" 2>&1
"${compose[@]}" run --rm -T --no-deps processor-worker --migrate >"${artifact_dir}/migrate.txt"
"${compose[@]}" up -d --wait api processor-worker >"${artifact_dir}/services-up.txt" 2>&1

# Warm-up (not reported): JIT, connection pools, and LocalStack's first-use costs.
"${compose[@]}" run --rm -T --no-deps processor-worker --synthetic-load=10 --synthetic-events=20 \
  --synthetic-prefix=warm >"${artifact_dir}/warm-import.txt"
wait_until "warm-up processed" 300 processed warm 200

# 1. Sustained ingest at the plan's burst rate: one event per game per round, rounds at a fixed rate.
total=$(( GAMES * EVENTS ))
pace_ms=$(( GAMES * 1000 / INGEST_RATE ))
"${compose[@]}" run --rm -T --no-deps processor-worker --synthetic-load="${GAMES}" \
  --synthetic-events="${EVENTS}" --synthetic-prefix=steady --synthetic-pace-ms="${pace_ms}" \
  >"${artifact_dir}/steady-import.txt"
wait_until "sustained ingest processed" 600 processed steady "${total}"
assert_no_suspension steady "${artifact_dir}/steady-import.txt"
IFS='|' read -r count wall rate p50 p95 p99 maximum <<<"$(latency_row steady)"
{
  echo "## 1. Sustained ingest at ${INGEST_RATE} events/s"
  echo
  echo "${GAMES} games x ${EVENTS} events, one event per game every ${pace_ms} ms (${INGEST_RATE} events/s, the"
  echo "plan's burst stress target). Latency is commit of the canonical event to commit of its"
  echo "checkpoint, the plan's processing delay (target: p95 below 500 ms)."
  echo
  echo "| Events | Wall time (s) | Throughput (events/s) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |"
  echo "| --- | --- | --- | --- | --- | --- | --- |"
  echo "| ${count} | ${wall} | ${rate} | ${p50} | ${p95} | ${p99} | ${maximum} |"
  echo
} >>"${report}"

# 2. Saturation: the same volume committed as fast as one importer can, to measure drain rate.
"${compose[@]}" run --rm -T --no-deps processor-worker --synthetic-load="${GAMES}" \
  --synthetic-events="${EVENTS}" --synthetic-prefix=burst >"${artifact_dir}/burst-import.txt"
wait_until "burst processed" 600 processed burst "${total}"
assert_no_suspension burst "${artifact_dir}/burst-import.txt"
IFS='|' read -r count wall rate p50 p95 p99 maximum <<<"$(latency_row burst)"
{
  echo "## 2. Saturation burst"
  echo
  echo "The same ${total} events committed back to back, far above any provider's ingress. Throughput is"
  echo "the drain rate; latency here is mostly time spent waiting behind earlier events of the same game."
  echo
  echo "| Events | Wall time (s) | Throughput (events/s) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |"
  echo "| --- | --- | --- | --- | --- | --- | --- |"
  echo "| ${count} | ${wall} | ${rate} | ${p50} | ${p95} | ${p99} | ${maximum} |"
  echo
  grep 'Synthetic load imported' "${artifact_dir}/burst-import.txt" | sed 's/^/Import: /'
  echo
} >>"${report}"

# 3. Hot games: 10 games x 1,000 owned rules (the per-game cap), processed concurrently.
"${compose[@]}" stop processor-worker >/dev/null 2>&1
"${compose[@]}" run --rm -T --no-deps processor-worker --synthetic-load=10 --synthetic-events=60 \
  --synthetic-prefix=hot >"${artifact_dir}/hot-import.txt"
database >/dev/null <<'SQL'
INSERT INTO application_users(subject, created_at, last_seen_at)
SELECT 'hot-user-' || lpad(u::text, 4, '0'), now(), now() FROM generate_series(1, 200) u;
INSERT INTO alert_rules(id, owner_subject, game_id, rule_type, enabled, player_id, points_threshold,
  client_request_id, request_fingerprint, version, created_at, updated_at)
SELECT gen_random_uuid(), 'hot-user-' || lpad(u::text, 4, '0'), 'hot-game-' || lpad(g::text, 4, '0'),
  'PLAYER_POINTS', true, 'hot-star-' || lpad(g::text, 4, '0'), t * 5,
  'hot-' || u || '-' || g || '-' || t, repeat('e', 64), 1, now(), now()
FROM generate_series(1, 200) u, generate_series(1, 10) g, generate_series(1, 5) t;
-- 90,000 rules on 90 other games (50 per user, the per-user cap) bring the table to the plan's
-- 100,000 stored rules, so the lookup below is measured at full scale.
INSERT INTO games(id, source, home_team_id, away_team_id, status, created_at, updated_at)
SELECT 'bulk-game-' || lpad(g::text, 4, '0'), 'courtpulse-load', 'bulk-home-' || g, 'bulk-away-' || g,
  'SCHEDULED', now(), now()
FROM generate_series(1, 90) g;
INSERT INTO application_users(subject, created_at, last_seen_at)
SELECT 'bulk-user-' || lpad(u::text, 5, '0'), now(), now() FROM generate_series(1, 1800) u;
INSERT INTO alert_rules(id, owner_subject, game_id, rule_type, enabled, player_id, points_threshold,
  client_request_id, request_fingerprint, version, created_at, updated_at)
SELECT gen_random_uuid(), 'bulk-user-' || lpad((((g - 1) * 1000 + r - 1) / 50 + 1)::text, 5, '0'),
  'bulk-game-' || lpad(g::text, 4, '0'), 'PLAYER_POINTS', true, 'bulk-player-' || (r % 30),
  10 + (r % 4) * 5, 'bulk-' || g || '-' || r, repeat('f', 64), 1, now(), now()
FROM generate_series(1, 90) g, generate_series(1, 1000) r;
ANALYZE alert_rules;
SQL
database -c "EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
  SELECT id FROM alert_rules WHERE enabled AND game_id = 'hot-game-0001' AND (
    (rule_type = 'CLOSE_GAME' AND eligible_period = 4 AND maximum_clock_millis_remaining >= 0 AND maximum_margin >= 0)
    OR (rule_type = 'PLAYER_POINTS' AND player_id = 'hot-star-0001')
    OR (rule_type = 'SCORING_RUN' AND true AND team_id = 'hot-home-0001'))
  ORDER BY id LIMIT 1002 FOR SHARE" >"${artifact_dir}/hot-rule-lookup-plan.txt"
hot_started="$(database -c "SELECT now()")"
"${compose[@]}" start processor-worker >/dev/null 2>&1
wait_until "hot games processed" 600 processed hot 600
hot="$(database -c "WITH p AS (
    SELECT c.game_id, p.processed_at,
      lag(p.processed_at) OVER (PARTITION BY c.game_id ORDER BY c.sequence_number) AS previous
    FROM canonical_events c JOIN processed_events p USING (event_id) WHERE c.game_id LIKE 'hot-%')
  SELECT round(extract(epoch FROM max(processed_at) - '${hot_started}'::timestamptz)::numeric, 2),
    round((percentile_cont(0.50) WITHIN GROUP (ORDER BY extract(epoch FROM processed_at - previous)) * 1000)::numeric),
    round((percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM processed_at - previous)) * 1000)::numeric),
    round((max(extract(epoch FROM processed_at - previous)) * 1000)::numeric)
  FROM p")"
IFS='|' read -r hot_wall hot_p50 hot_p95 hot_max <<<"${hot}"
stored_rules="$(database -c "SELECT count(*) FROM alert_rules")"
evaluations="$(database -c "SELECT count(*) * 1000 FROM canonical_events
  WHERE game_id LIKE 'hot-%' AND participant_ids->>0 LIKE 'hot-star-%'")"
alerts="$(database -c "SELECT count(*) FROM alert_instances WHERE game_id LIKE 'hot-%'")"
index_line="$(grep -E '(Index|Seq) Scan on' "${artifact_dir}/hot-rule-lookup-plan.txt" \
  | sed -E 's/^[[:space:]>-]*((Bitmap Index|Index Only|Index|Seq) Scan on [a-z_]+).* rows=([0-9]+) .*/\1 (\3 rows)/' \
  | paste -sd ';' - | sed 's/;/; /g')"
lookup_ms="$(sed -n 's/^Execution Time: \([0-9.]*\) ms/\1/p' "${artifact_dir}/hot-rule-lookup-plan.txt")"
{
  echo "## 3. Hot games (rule fanout)"
  echo
  echo "10 games x 60 events with 1,000 owned PLAYER_POINTS rules each (the enforced per-game cap)"
  echo "on each game's star player, processed concurrently from a cold queue. ${stored_rules} rules are"
  echo "stored in total (90,000 more on 90 other games), the plan's upper bound for stored rules."
  echo
  echo "| Rule evaluations | Alerts created | Wall time (s) | Per-event step p50 (ms) | p95 (ms) | max (ms) |"
  echo "| --- | --- | --- | --- | --- | --- |"
  echo "| ${evaluations} | ${alerts} | ${hot_wall} | ${hot_p50} | ${hot_p95} | ${hot_max} |"
  echo
  echo "Rule lookup for one hot game took ${lookup_ms} ms using ${index_line} (full plan in the"
  echo "run artifacts)."
  echo
} >>"${report}"

# 4. Public read API under an arrival-rate model.
k6 "${k6_image}" run --quiet -e BASE_URL=http://api:8080 -e PEAK_RATE="${PEAK_RATE}" \
  --summary-export=/results/k6-read.json /scripts/read-api.js >"${artifact_dir}/k6-read.txt" 2>&1 || true
{
  echo "## 4. Read API"
  echo
  echo "k6 ramping arrival rate to ${PEAK_RATE} iterations/s (3 requests each: game list, conditional"
  echo "snapshot, event page) for 90 s."
  echo
  echo "| Requests | Failed | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |"
  echo "| --- | --- | --- | --- | --- | --- |"
  echo "| $(k6_metric k6-read.json '.metrics.http_reqs.count') | $(jq -r '(.metrics.http_req_failed.value // .metrics.http_req_failed.rate // 0) * 100 | tostring + "%"' "${artifact_dir}/k6-read.json") | $(k6_metric k6-read.json '.metrics.http_req_duration["p(50)"] // .metrics.http_req_duration.med') | $(k6_metric k6-read.json '.metrics.http_req_duration["p(95)"]') | $(k6_metric k6-read.json '.metrics.http_req_duration["p(99)"]') | $(k6_metric k6-read.json '.metrics.http_req_duration.max') |"
  echo
} >>"${report}"

# 5. Realtime fanout while a paced game produces hints. The game starts first so every
#    subscription targets an existing game; subscribers then join over ~20 s as real clients do.
"${compose[@]}" run --rm -T --no-deps processor-worker --synthetic-load=1 --synthetic-events=180 \
  --synthetic-prefix=live --synthetic-pace-ms=500 >"${artifact_dir}/live-import.txt" &
live_import=$!
wait_until "live game visible" 60 processed live 1
docker run -d --name "${project}-k6-ws" --network "${project}_default" \
  -v "${repository}/load/k6:/scripts:ro" -v "${artifact_dir}:/results" \
  "${k6_image}" run --quiet -e WS_URL=ws://api:8080/ws/v1/games \
  -e GAME_ID=live-game-0001 -e CLIENTS="${CLIENTS}" -e HOLD_SECONDS=70 \
  --summary-export=/results/k6-ws.json /scripts/websocket-fanout.js >/dev/null
wait "${live_import}"
docker wait "${project}-k6-ws" >/dev/null
docker logs "${project}-k6-ws" >"${artifact_dir}/k6-ws.txt" 2>&1 || true
docker rm "${project}-k6-ws" >/dev/null 2>&1 || true
publish="$(database -c "SELECT
    round((percentile_cont(0.50) WITHIN GROUP (ORDER BY extract(epoch FROM published_at - created_at)) * 1000)::numeric),
    round((percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM published_at - created_at)) * 1000)::numeric)
  FROM outbox WHERE destination = 'FUTURE_NOTIFICATIONS' AND aggregate_id = 'live-game-0001' AND published_at IS NOT NULL")"
IFS='|' read -r publish_p50 publish_p95 <<<"${publish}"
{
  echo "## 5. Realtime fanout"
  echo
  echo "${CLIENTS} WebSocket subscribers on one game while it receives 180 events at 2 per second."
  echo "Fanout latency is the server's \`emittedAt\` to receipt in k6 (same host clock); the outbox"
  echo "delay is checkpoint commit to hint emission (the API polls the realtime outbox every 250 ms)."
  echo
  echo "| Subscribers | Hints received | Fanout p50 (ms) | Fanout p95 (ms) | Fanout max (ms) | Outbox p50 (ms) | Outbox p95 (ms) | Protocol problems |"
  echo "| --- | --- | --- | --- | --- | --- | --- | --- |"
  echo "| ${CLIENTS} | $(k6_metric k6-ws.json '.metrics.realtime_hints_received.count') | $(k6_metric k6-ws.json '.metrics.realtime_hint_latency["p(50)"] // .metrics.realtime_hint_latency.med') | $(k6_metric k6-ws.json '.metrics.realtime_hint_latency["p(95)"]') | $(k6_metric k6-ws.json '.metrics.realtime_hint_latency.max') | ${publish_p50} | ${publish_p95} | $(k6_metric k6-ws.json '.metrics.realtime_problems.count') |"
  echo
  echo "Upgrade checks: $(jq -r '.root_group.checks["upgraded to WebSocket"] | "\(.passes) passed, \(.fails) failed"' "${artifact_dir}/k6-ws.json" 2>/dev/null || echo n/a)."
} >>"${report}"

cat "${report}"
echo
echo "Performance report: ${report}"
