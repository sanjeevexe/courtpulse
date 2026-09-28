#!/usr/bin/env bash
# Failure drills in an isolated Compose project. Each drill interrupts a dependency while a burst
# is being processed, lets the system recover on its own (restart policies, leases, retries), and
# proves: every accepted event processed exactly once, every game's checkpoint complete, and one
# alert per logical trigger. Recovery times are measured, not assumed.
set -Eeuo pipefail

# Recovery times are wall-clock; keep the host awake so a suspended Docker VM is not measured.
if command -v caffeinate >/dev/null 2>&1 && [[ -z "${COURTPULSE_CAFFEINATED:-}" ]]; then
  COURTPULSE_CAFFEINATED=1 exec caffeinate -i "$0" "$@"
fi

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
suffix="$(openssl rand -hex 4)"
project="courtpulse-recovery-${suffix}"
artifact_dir="${repository}/build/verification/recovery/${project}"
report="${artifact_dir}/report.md"
mkdir -p "${artifact_dir}"

GAMES=20
EVENTS=40
DRILLS="${DRILLS:-kill postgres sqs deploy}"   # run a subset, e.g. DRILLS=sqs
COURTPULSE_DB_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_DB_PASSWORD
export COURTPULSE_CONSUMER_WORKERS=2
export COURTPULSE_POSTGRES_HOST_PORT=58033
export COURTPULSE_LOCALSTACK_HOST_PORT=57167
export COURTPULSE_API_HOST_PORT=60781
export COURTPULSE_WEB_HOST_PORT=56873
api="http://127.0.0.1:${COURTPULSE_API_HOST_PORT}"

compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml"
  -p "${project}" --profile live --profile reconciliation)

database() {
  "${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -At -F '|' -U courtpulse -d courtpulse "$@"
}

processed_count() {
  database -c "SELECT count(*) FROM processed_events p JOIN canonical_events c USING (event_id)
    WHERE c.game_id LIKE '$1-%'" 2>/dev/null || echo 0
}

all_processed() { [[ "$(processed_count "$1")" == "$(( GAMES * EVENTS ))" ]]; }
some_processed() { (( $(processed_count "$1") >= $2 )); }
api_ready() { curl -fsS "${api}/actuator/health/readiness" >/dev/null 2>&1; }

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

burst() {
  "${compose[@]}" run --rm -T --no-deps processor-worker --synthetic-load="${GAMES}" \
    --synthetic-events="${EVENTS}" --synthetic-prefix="$1" >"${artifact_dir}/$1-import.txt"
}

# One owned rule per game on the star player; exactly one alert per game proves no duplicates.
rules() {
  database >/dev/null <<SQL
INSERT INTO application_users(subject, created_at, last_seen_at) VALUES ('$1-fan', now(), now())
ON CONFLICT DO NOTHING;
INSERT INTO alert_rules(id, owner_subject, game_id, rule_type, enabled, player_id, points_threshold,
  client_request_id, request_fingerprint, version, created_at, updated_at)
SELECT gen_random_uuid(), '$1-fan', '$1-game-' || lpad(g::text, 4, '0'), 'PLAYER_POINTS', true,
  '$1-star-' || lpad(g::text, 4, '0'), 2, '$1-rule-' || g, repeat('b', 64), 1, now(), now()
FROM generate_series(1, ${GAMES}) g;
SQL
}

assert_integrity() {
  local prefix="$1" result
  result="$(database -c "SELECT
      (SELECT count(*) FROM processed_events p JOIN canonical_events c USING (event_id)
        WHERE c.game_id LIKE '${prefix}-%'),
      (SELECT count(*) FROM game_checkpoints WHERE game_id LIKE '${prefix}-%'
        AND status = 'FINAL' AND last_sequence = ${EVENTS}),
      (SELECT count(*) FROM alert_instances WHERE game_id LIKE '${prefix}-%'),
      (SELECT count(DISTINCT game_id) FROM alert_instances WHERE game_id LIKE '${prefix}-%'),
      (SELECT count(*) FROM outbox WHERE destination = 'GAME_EVENTS' AND status = 'FAILED')")"
  echo "${prefix}: processed|final checkpoints|alerts|games with an alert|failed outbox = ${result}" \
    | tee -a "${artifact_dir}/integrity.txt"
  [[ "${result}" == "$(( GAMES * EVENTS ))|${GAMES}|${GAMES}|${GAMES}|0" ]]
}

finish() {
  local status=$?
  [[ -z "${sampler:-}" ]] || kill "${sampler}" 2>/dev/null || true
  if [[ ${status} -ne 0 ]]; then
    database -c "SELECT status, attempts, lease_owner IS NOT NULL AS leased, lease_expires_at, next_attempt_at,
        left(coalesce(last_error, ''), 200), count(*), min(aggregate_id)
      FROM outbox WHERE destination = 'GAME_EVENTS' AND status <> 'SENT'
      GROUP BY 1, 2, 3, 4, 5, 6 ORDER BY 8" >"${artifact_dir}/unsent-outbox.txt" 2>&1 || true
    database -c "SELECT now()" >>"${artifact_dir}/unsent-outbox.txt" 2>&1 || true
  fi
  "${compose[@]}" ps -a >"${artifact_dir}/compose-ps.txt" 2>&1 || true
  "${compose[@]}" logs --no-color >"${artifact_dir}/compose.log" 2>&1 || true
  "${compose[@]}" down -v --rmi local --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  [[ ${status} -eq 0 ]] || echo "Recovery drills failed; evidence is in ${artifact_dir}" >&2
  exit "${status}"
}
trap finish EXIT

cd "${repository}"
"${compose[@]}" build api processor-worker reconciliation-worker >"${artifact_dir}/build.txt" 2>&1
"${compose[@]}" up -d --wait postgres localstack >"${artifact_dir}/infrastructure-up.txt" 2>&1
"${compose[@]}" run --rm -T --no-deps processor-worker --migrate >"${artifact_dir}/migrate.txt"
"${compose[@]}" up -d --wait api processor-worker reconciliation-worker >"${artifact_dir}/services-up.txt" 2>&1
{
  echo "# CourtPulse recovery drills"
  echo
  echo "- Date (UTC): $(date -u '+%Y-%m-%d %H:%M'); commit \`$(git rev-parse --short HEAD)\`"
  echo "- Each drill: ${GAMES} games x ${EVENTS} events in a burst, one owned rule per game."
  echo
  echo "| Drill | Interruption | Recovery to all events processed | Integrity |"
  echo "| --- | --- | --- | --- |"
} >"${report}"

# Drill 1: the processor is killed without warning partway through a burst.
if [[ " ${DRILLS} " == *" kill "* ]]; then
  "${compose[@]}" stop processor-worker >/dev/null 2>&1
  burst kill && rules kill
  "${compose[@]}" start processor-worker >/dev/null 2>&1
  wait_until 'first quarter of the kill burst' 180 some_processed kill $(( GAMES * EVENTS / 4 ))
  docker kill --signal KILL "${project}-processor-worker-1" >/dev/null
  killed_at=${SECONDS}
  sleep 3
  "${compose[@]}" start processor-worker >/dev/null 2>&1
  wait_until 'kill burst fully processed' 300 all_processed kill
  assert_integrity kill
  echo "| Processor SIGKILL mid-burst | killed, restarted after 3 s | $(( SECONDS - killed_at )) s | exactly once, one alert per game |" >>"${report}"
fi

# Drill 2: PostgreSQL restarts while events are being processed.
if [[ " ${DRILLS} " == *" postgres "* ]]; then
  "${compose[@]}" stop processor-worker >/dev/null 2>&1
  burst postgres && rules postgres
  "${compose[@]}" start processor-worker >/dev/null 2>&1
  wait_until 'first quarter of the database burst' 180 some_processed postgres $(( GAMES * EVENTS / 4 ))
  restarted_at=${SECONDS}
  "${compose[@]}" restart postgres >"${artifact_dir}/postgres-restart.txt" 2>&1
  wait_until 'API readiness after database restart' 120 api_ready
  api_recovery=$(( SECONDS - restarted_at ))
  (( api_recovery > 0 )) || api_recovery='under 1'
  wait_until 'database burst fully processed' 300 all_processed postgres
  assert_integrity postgres
  echo "| PostgreSQL restart mid-burst | database restarted (API ready again after ${api_recovery} s) | $(( SECONDS - restarted_at )) s | exactly once, one alert per game |" >>"${report}"
fi

# Drill 3: SQS unreachable for 20 seconds. LocalStack is detached from the network rather than
# stopped: stopping it would discard its in-memory queues, which real SQS never does. Publishers
# retry with capped backoff; consumers fail fast and their restart policy brings them back. The
# processor is recreated with a one-row publisher so publication is still running when SQS drops.
if [[ " ${DRILLS} " == *" sqs "* ]]; then
  "${compose[@]}" stop processor-worker >/dev/null 2>&1
  burst sqs && rules sqs
  COURTPULSE_PUBLISHER_BATCH_SIZE=1 "${compose[@]}" up -d --no-deps processor-worker >/dev/null 2>&1
  wait_until 'first publications of the queue burst' 180 some_processed sqs 40
  sent_before="$(database -c "SELECT count(*) FROM outbox WHERE destination = 'GAME_EVENTS'
    AND aggregate_id LIKE 'sqs-%' AND status = 'SENT'")"
  outage_at=${SECONDS}
  # Sample progress every second so the report can say where recovery time goes.
  ( while :; do
      echo "$(( SECONDS - outage_at )) s: sent $(database -c "SELECT count(*) FROM outbox WHERE destination = 'GAME_EVENTS'
        AND aggregate_id LIKE 'sqs-%' AND status = 'SENT'" 2>/dev/null), processed $(processed_count sqs)"
      sleep 1
    done ) >"${artifact_dir}/sqs-timeline.txt" 2>&1 &
  sampler=$!
  disown "${sampler}"   # no job-control notice when it is stopped
  docker network disconnect "${project}_default" "${project}-localstack-1"
  sleep 20
  docker network connect --alias localstack "${project}_default" "${project}-localstack-1"
  wait_until 'queue burst fully processed' 300 all_processed sqs
  kill "${sampler}" 2>/dev/null || true
  assert_integrity sqs
  restarts="$(docker inspect --format '{{.RestartCount}}' "${project}-processor-worker-1")"
  retried="$(database -c "SELECT count(*) FROM outbox WHERE destination = 'GAME_EVENTS'
    AND aggregate_id LIKE 'sqs-%' AND attempts > 1")"
  if (( retried == 0 )); then
    echo "The outage did not overlap publication; the drill proved nothing about publisher retries" >&2
    exit 1
  fi
  echo "| SQS unreachable for 20 s mid-burst (network partition) | ${sent_before} of $(( GAMES * EVENTS )) published before the partition; ${retried} publications retried; processor restarted ${restarts} time(s) by its restart policy | $(( SECONDS - outage_at )) s | exactly once, one alert per game |" >>"${report}"
fi

# Drill 4: a rolling deploy sends SIGTERM while the publisher is mid-publication. Workers get a
# grace period to finish their current step, so no send is aborted into an unknown outcome.
if [[ " ${DRILLS} " == *" deploy "* ]]; then
  "${compose[@]}" stop processor-worker >/dev/null 2>&1
  burst deploy && rules deploy
  COURTPULSE_PUBLISHER_BATCH_SIZE=1 "${compose[@]}" up -d --no-deps processor-worker >/dev/null 2>&1
  wait_until 'first publications of the deploy burst' 180 some_processed deploy 40
  stopped_at=${SECONDS}
  "${compose[@]}" stop processor-worker >/dev/null 2>&1
  stop_seconds=$(( SECONDS - stopped_at ))
  (( stop_seconds > 0 )) || stop_seconds='under 1'  # SECONDS has one-second resolution
  "${compose[@]}" start processor-worker >/dev/null 2>&1
  wait_until 'deploy burst fully processed' 300 all_processed deploy
  assert_integrity deploy
  echo "| Processor SIGTERM mid-publication (rolling deploy) | graceful stop took ${stop_seconds} s, then restarted | $(( SECONDS - stopped_at )) s | exactly once, one alert per game |" >>"${report}"
fi

{
  echo
  echo "Integrity means: every accepted event has exactly one processed identity, every game has a"
  echo "FINAL checkpoint at sequence ${EVENTS}, exactly one private alert exists per game for its rule, and"
  echo "no game-event outbox row is FAILED."
} >>"${report}"
cat "${report}"
