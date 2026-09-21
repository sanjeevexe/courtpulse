#!/usr/bin/env bash
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project="courtpulse-m6-acceptance"
password="courtpulse-m6-isolated"
artifact_dir="${repository}/build/verification/milestone-6"
queue_url="http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/game-events.fifo"
dlq_url="http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/game-events-dlq.fifo"

export COURTPULSE_DB_PASSWORD="${password}"
export COURTPULSE_POSTGRES_HOST_PORT=55432
export COURTPULSE_LOCALSTACK_HOST_PORT=54566
export COURTPULSE_API_HOST_PORT=58080
export COURTPULSE_WEB_HOST_PORT=54173

mkdir -p "${artifact_dir}"

compose() {
  docker compose --project-directory "${repository}" -f "${repository}/compose.yaml" \
    -p "${project}" "$@"
}

database() {
  compose exec -T postgres psql -v ON_ERROR_STOP=1 -U courtpulse -d courtpulse "$@"
}

queue_attribute() {
  local url="$1"
  local name="$2"
  compose exec -T localstack awslocal sqs get-queue-attributes \
    --queue-url "${url}" --attribute-names "${name}" \
    --query "Attributes.${name}" --output text | tr -d '\r'
}

queue_replay() {
  env \
    COURTPULSE_DB_URL='jdbc:postgresql://localhost:55432/courtpulse' \
    COURTPULSE_DB_USERNAME=courtpulse \
    COURTPULSE_DB_PASSWORD="${password}" \
    COURTPULSE_SQS_ENDPOINT='http://localhost:54566' \
    "${repository}/gradlew" :apps:queue-replay-cli:run --args="$1"
}

diagnostics() {
  compose ps -a >"${artifact_dir}/compose-ps.txt" 2>&1 || true
  compose logs --no-color >"${artifact_dir}/compose.log" 2>&1 || true
  database -At -F '|' -c \
    "SELECT destination,status,count(*) FROM outbox GROUP BY destination,status ORDER BY destination,status" \
    >"${artifact_dir}/outbox-status.txt" 2>&1 || true
  database -At -F '|' -c \
    "SELECT game_id,state_version,home_score,away_score,state_checksum FROM game_checkpoints" \
    >"${artifact_dir}/checkpoint.txt" 2>&1 || true
  queue_attribute "${queue_url}" ApproximateNumberOfMessages \
    >"${artifact_dir}/queue-visible.txt" 2>&1 || true
  queue_attribute "${queue_url}" ApproximateNumberOfMessagesNotVisible \
    >"${artifact_dir}/queue-in-flight.txt" 2>&1 || true
}

finish() {
  local status=$?
  diagnostics
  compose down -v --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  exit "${status}"
}
trap finish EXIT

cd "${repository}"
compose down -v --remove-orphans >/dev/null 2>&1 || true
compose up -d --build postgres localstack api web | tee "${artifact_dir}/compose-up.txt"

for attempt in {1..90}; do
  if curl -fsS 'http://127.0.0.1:54173/healthz' >/dev/null \
    && curl -fsS 'http://127.0.0.1:58080/actuator/health/readiness' >/dev/null; then
    break
  fi
  if [[ "${attempt}" == 90 ]]; then
    echo 'Timed out waiting for the isolated stack health checks' >&2
    exit 1
  fi
  sleep 1
done

queue_replay '--reset-import --publish --courtpulse.consumer.batch-size=1' \
  | tee "${artifact_dir}/seed-publish.txt"

visible="$(queue_attribute "${queue_url}" ApproximateNumberOfMessages)"
in_flight="$(queue_attribute "${queue_url}" ApproximateNumberOfMessagesNotVisible)"
[[ "${visible}" == 20 && "${in_flight}" == 0 ]] || {
  echo "Expected 20 visible queue messages before replay; got visible=${visible} inFlight=${in_flight}" >&2
  exit 1
}

queue_replay '--drain --pace-ms=1000 --courtpulse.consumer.batch-size=1' \
  >"${artifact_dir}/paced-drain.txt" 2>&1 &
drain_pid=$!

(
  for attempt in {1..300}; do
    ready="$(database -At -c \
      "SELECT CASE WHEN COALESCE((SELECT state_version FROM game_checkpoints WHERE game_id='game_synthetic_001'),0)=11 AND (SELECT count(*) FROM outbox WHERE destination='FUTURE_NOTIFICATIONS' AND event_type='ALERT_CREATED' AND status='SENT')=1 THEN 1 ELSE 0 END")"
    if [[ "${ready}" == 1 ]]; then
      database -c \
        "UPDATE outbox SET status='PENDING', published_at=NULL, next_attempt_at=now(), lease_owner=NULL, lease_expires_at=NULL WHERE destination='FUTURE_NOTIFICATIONS' AND event_type='ALERT_CREATED'" \
        >"${artifact_dir}/duplicate-injection.txt"
      exit 0
    fi
    sleep 0.05
  done
  echo 'Timed out waiting to inject duplicate realtime alert delivery' >&2
  exit 1
) &
duplicate_pid=$!

for attempt in {1..200}; do
  snapshot="$(curl -fsS 'http://127.0.0.1:54173/api/v1/games/game_synthetic_001' 2>/dev/null || true)"
  version="$(jq -r '.stateVersion // 0' <<<"${snapshot}" 2>/dev/null || echo 0)"
  if (( version >= 1 && version < 20 )); then
    printf 'Browser start checkpoint: v%s\n' "${version}" | tee "${artifact_dir}/browser-checkpoint.txt"
    break
  fi
  if [[ "${attempt}" == 200 ]]; then
    echo "Did not observe an early replay checkpoint; last version=${version}" >&2
    exit 1
  fi
  sleep 0.1
done

(
  cd "${repository}/apps/web"
  env \
    PLAYWRIGHT_BASE_URL='http://127.0.0.1:54173' \
    COURTPULSE_PACED_ACCEPTANCE=1 \
    COURTPULSE_EXPECT_DUPLICATE=1 \
    npx playwright test e2e/realtime-acceptance.spec.ts --workers=2 --retries=0
) | tee "${artifact_dir}/paced-browser.txt"

wait "${drain_pid}"
wait "${duplicate_pid}"

durable="$(database -At -F '|' -c "
  SELECT
    (SELECT count(*) FROM processed_events),
    checkpoint.state_version,
    (SELECT count(*) FROM alert_instances),
    checkpoint.home_score,
    checkpoint.away_score,
    checkpoint.player_points ->> 'player_ace',
    checkpoint.state_checksum,
    (SELECT count(*) FROM outbox WHERE destination='FUTURE_NOTIFICATIONS' AND status='SENT'),
    (SELECT count(*) FROM outbox WHERE destination='FUTURE_NOTIFICATIONS' AND status<>'SENT')
  FROM game_checkpoints checkpoint
  WHERE checkpoint.game_id='game_synthetic_001'
")"
expected='20|20|1|18|14|13|06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca|21|0'
printf '%s\n' "${durable}" | tee "${artifact_dir}/durable-result.txt"
[[ "${durable}" == "${expected}" ]] || {
  echo "Unexpected durable result; expected ${expected}" >&2
  exit 1
}

visible="$(queue_attribute "${queue_url}" ApproximateNumberOfMessages)"
in_flight="$(queue_attribute "${queue_url}" ApproximateNumberOfMessagesNotVisible)"
dlq_visible="$(queue_attribute "${dlq_url}" ApproximateNumberOfMessages)"
[[ "${visible}" == 0 && "${in_flight}" == 0 && "${dlq_visible}" == 0 ]] || {
  echo "Queues not drained: visible=${visible} inFlight=${in_flight} dlq=${dlq_visible}" >&2
  exit 1
}

(
  cd "${repository}/apps/web"
  env \
    PLAYWRIGHT_BASE_URL='http://127.0.0.1:54173' \
    COURTPULSE_ISOLATED_RECOVERY=1 \
    COURTPULSE_DB_PASSWORD="${password}" \
    COURTPULSE_POSTGRES_HOST_PORT=55432 \
    COURTPULSE_LOCALSTACK_HOST_PORT=54566 \
    COURTPULSE_API_HOST_PORT=58080 \
    COURTPULSE_WEB_HOST_PORT=54173 \
    npx playwright test e2e/realtime-reconnect.spec.ts --workers=1
) | tee "${artifact_dir}/reconnect-browser.txt"

echo 'Milestone 6 isolated paced replay and reconnect acceptance passed.'
