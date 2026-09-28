#!/usr/bin/env bash
# Isolated M11 proof: one play crosses three instrumented processes in one trace, the API
# answers with a correlatable trace ID, metrics stay private, and real failures fire alerts.
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
suffix="$(openssl rand -hex 4)"
project="courtpulse-observability-v${suffix}"
artifact_dir="${repository}/build/verification/observability/${project}"
mkdir -p "${artifact_dir}"
chmod 700 "${artifact_dir}"
date -u '+started_at_utc=%Y-%m-%dT%H:%M:%SZ' >"${artifact_dir}/timing.txt"

for tool in docker curl jq openssl; do
  command -v "${tool}" >/dev/null || { echo "Required tool missing: ${tool}" >&2; exit 1; }
done

COURTPULSE_DB_PASSWORD="$(openssl rand -hex 18)"
COURTPULSE_METRICS_SCRAPE_PASSWORD="$(openssl rand -hex 24)"
COURTPULSE_GRAFANA_ADMIN_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_DB_PASSWORD COURTPULSE_METRICS_SCRAPE_PASSWORD COURTPULSE_GRAFANA_ADMIN_PASSWORD
export COURTPULSE_METRICS_SCRAPE_PASSWORD_FILE="${artifact_dir}/metrics-password"
printf '%s' "${COURTPULSE_METRICS_SCRAPE_PASSWORD}" >"${COURTPULSE_METRICS_SCRAPE_PASSWORD_FILE}"
chmod 644 "${COURTPULSE_METRICS_SCRAPE_PASSWORD_FILE}" # read by the non-root Prometheus user
export COURTPULSE_POSTGRES_HOST_PORT=57533
export COURTPULSE_LOCALSTACK_HOST_PORT=56667
export COURTPULSE_API_HOST_PORT=60281
export COURTPULSE_WEB_HOST_PORT=56373
export COURTPULSE_MAILPIT_SMTP_HOST_PORT=11225
export COURTPULSE_MAILPIT_UI_HOST_PORT=18225
export COURTPULSE_GRAFANA_HOST_PORT=13301

api="http://127.0.0.1:${COURTPULSE_API_HOST_PORT}"
grafana="http://127.0.0.1:${COURTPULSE_GRAFANA_HOST_PORT}"
mailpit="http://127.0.0.1:${COURTPULSE_MAILPIT_UI_HOST_PORT}"
prometheus_proxy="${grafana}/api/datasources/proxy/uid/courtpulse-prometheus"
tempo_proxy="${grafana}/api/datasources/proxy/uid/courtpulse-tempo"
private_address="obs-verify-${suffix}@example.test"
private_subject="obs-verify-subject-${suffix}"

compose=(docker compose --project-directory "${repository}" -p "${project}"
  -f "${repository}/compose.yaml" -f "${repository}/compose.observability.yaml"
  --profile delivery --profile reconciliation --profile observability)

database() {
  "${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -At -F '|' -U courtpulse -d courtpulse "$@"
}

# One-off instrumented worker commands; OTEL_SERVICE_NAME makes each process distinct in Tempo.
worker_run() {
  local service_name="$1" compose_service="$2"
  shift 2
  "${compose[@]}" run --rm -T --no-deps -e "OTEL_SERVICE_NAME=${service_name}" \
    "${compose_service}" "$@"
}

grafana_get() {
  curl -fsS -u "courtpulse:${COURTPULSE_GRAFANA_ADMIN_PASSWORD}" "$@"
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
    sleep 2
  done
  echo "${description}: $(( SECONDS - started ))s" | tee -a "${artifact_dir}/timing.txt"
}

alert_state() {
  grafana_get "${prometheus_proxy}/api/v1/alerts" 2>/dev/null \
    | jq -r --arg name "$1" '[.data.alerts[] | select(.labels.alertname == $name) | .state]
        | if index("firing") then "firing" elif length > 0 then .[0] else "inactive" end'
}

alert_firing() { [[ "$(alert_state "$1")" == firing ]]; }
alert_resolved() { [[ "$(alert_state "$1")" == inactive ]]; }

container_healthy() {
  [[ "$(docker inspect --format '{{.State.Health.Status}}' "${project}-$1-1" 2>/dev/null || true)" == healthy ]]
}

infrastructure_ready() {
  container_healthy postgres && container_healthy localstack && container_healthy mailpit
}

application_ready() {
  curl -fsS "${api}/actuator/health/readiness" >/dev/null 2>&1 \
    && container_healthy delivery-worker && container_healthy reconciliation-worker \
    && grafana_get "${grafana}/api/health" >/dev/null 2>&1
}

prometheus_target_up() {
  [[ "$(grafana_get --get "${prometheus_proxy}/api/v1/query" \
      --data-urlencode 'query=up{job="courtpulse-api"}' 2>/dev/null \
      | jq -r '.data.result[0].value[1] // "0"')" == 1 ]]
}

email_sent() {
  [[ "$(database -c "SELECT count(*) FROM delivery_attempts WHERE outcome='SENT'")" == 1 ]]
}

reconciliation_status_is() {
  [[ "$(database -c "SELECT status FROM game_reconciliations WHERE game_id='game_gap_001'")" == "$1" ]]
}

# Prints the first trace ID whose root trace contains the given service and span name.
find_trace() {
  local query="{ resource.service.name = \"$1\" && name = \"$2\" }"
  grafana_get --get "${tempo_proxy}/api/search" --data-urlencode "q=${query}" \
    --data-urlencode 'limit=20' 2>/dev/null | jq -r '.traces[0].traceID // empty'
}

# Writes "service<TAB>span" pairs for a trace; tolerant of Tempo's v1 OTLP JSON shapes.
trace_spans() {
  grafana_get "${tempo_proxy}/api/traces/$1" >"${artifact_dir}/trace-$1.json" 2>/dev/null || return 1
  jq -r '(.batches // .resourceSpans // [])[]
      | ((.resource.attributes // [])[] | select(.key == "service.name") | .value.stringValue) as $service
      | ((.scopeSpans // .instrumentationLibrarySpans // [])[] | .spans[] | [$service, .name])
      | @tsv' "${artifact_dir}/trace-$1.json" | sort -u
}

trace_contains_all() {
  local trace_id="$1" spans
  shift
  spans="$(trace_spans "${trace_id}")" || return 1
  local required
  for required in "$@"; do
    grep -qxF "${required}" <<<"${spans}" || return 1
  done
}

captured_trace=""
capture_trace() {
  local service="$1" span="$2"
  shift 2
  captured_trace="$(find_trace "${service}" "${span}")"
  [[ -n "${captured_trace}" ]] && trace_contains_all "${captured_trace}" "$@"
}

finish() {
  local status=$?
  date -u '+finished_at_utc=%Y-%m-%dT%H:%M:%SZ' >>"${artifact_dir}/timing.txt"
  grafana_get "${prometheus_proxy}/api/v1/alerts" >"${artifact_dir}/alerts-final.json" 2>/dev/null || true
  "${compose[@]}" ps -a >"${artifact_dir}/compose-ps.txt" 2>&1 || true
  "${compose[@]}" logs --no-color >"${artifact_dir}/compose.log" 2>&1 || true
  database -c "SELECT worker_type, observed_at, consecutive_failures FROM worker_heartbeats ORDER BY 1" \
    >"${artifact_dir}/worker-heartbeats.txt" 2>&1 || true
  database -c "SELECT queue_type, visible, in_flight, delayed, observed_at FROM queue_observations ORDER BY 1" \
    >"${artifact_dir}/queue-observations.txt" 2>&1 || true
  "${compose[@]}" down -v --rmi local --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  rm -f "${COURTPULSE_METRICS_SCRAPE_PASSWORD_FILE}"
  if [[ ${status} -ne 0 ]]; then
    echo "Observability verification failed; evidence is in ${artifact_dir}" >&2
  fi
  exit "${status}"
}
trap finish EXIT

cd "${repository}"
"${compose[@]}" build api delivery-worker reconciliation-worker >"${artifact_dir}/build.txt" 2>&1
"${compose[@]}" up -d postgres localstack mailpit otel-collector tempo >"${artifact_dir}/infrastructure-up.txt" 2>&1
wait_until 'PostgreSQL, LocalStack, and Mailpit healthy' 180 infrastructure_ready

# 1. Instrumented import records the ingest span context on every outbox row.
worker_run courtpulse-ingest delivery-worker --reset-import --inspect >"${artifact_dir}/fixture-import.txt"
[[ "$(database -c "SELECT count(*) FROM outbox WHERE destination='GAME_EVENTS' AND traceparent IS NOT NULL")" == 20 ]] \
  || { echo 'Imported outbox rows did not carry trace context' >&2; exit 1; }

# 2. One private user rule with an enabled email destination, so delivery joins the trace.
database >/dev/null <<SQL
INSERT INTO application_users(subject, created_at, last_seen_at) VALUES ('${private_subject}', now(), now());
INSERT INTO alert_rules(id, owner_subject, game_id, rule_type, enabled, player_id, points_threshold,
  client_request_id, request_fingerprint, version, created_at, updated_at)
VALUES (gen_random_uuid(), '${private_subject}', 'game_synthetic_001', 'PLAYER_POINTS', true,
  'player_ace', 10, 'obs-verify-ace-10', repeat('b', 64), 1, now(), now());
INSERT INTO notification_preferences(owner_subject, in_app_enabled, email_enabled, updated_at)
VALUES ('${private_subject}', true, true, now());
INSERT INTO notification_destinations(id, owner_subject, channel, address, enabled, created_at, updated_at)
VALUES (gen_random_uuid(), '${private_subject}', 'EMAIL', '${private_address}', true, now(), now());
SQL

"${compose[@]}" up -d api delivery-worker reconciliation-worker prometheus grafana \
  >"${artifact_dir}/application-up.txt" 2>&1
wait_until 'API, workers, and Grafana healthy' 180 application_ready
wait_until 'Prometheus scraping the API' 120 prometheus_target_up

# 3. Instrumented publish/consume/process in a separate process; the daemon then emails.
worker_run courtpulse-processor delivery-worker --run >"${artifact_dir}/processor-run.txt"
grep -q 'Final-state checksum: 06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca' \
  "${artifact_dir}/processor-run.txt" || { echo 'Replay checksum changed under instrumentation' >&2; exit 1; }
wait_until 'private alert email delivered' 90 email_sent
[[ "$(curl -fsS "${mailpit}/api/v1/messages" | jq -r '.total')" == 1 ]]

wait_until 'one trace spanning ingest, processor, and delivery-worker processes' 120 capture_trace \
  courtpulse-delivery-worker 'alert-delivery consume' \
  $'courtpulse-ingest\tfixture ingest' \
  $'courtpulse-processor\tgame-event publish' \
  $'courtpulse-processor\tgame-event consume' \
  $'courtpulse-processor\tgame-event process' \
  $'courtpulse-processor\talert-rule evaluate' \
  $'courtpulse-delivery-worker\talert-delivery publish' \
  $'courtpulse-delivery-worker\talert-delivery consume'
delivery_trace="${captured_trace}"
trace_spans "${delivery_trace}" >"${artifact_dir}/delivery-trace-spans.tsv"
printf 'delivery_trace_id=%s spans=%s services=%s\n' "${delivery_trace}" \
  "$(jq '[(.batches // .resourceSpans // [])[] | (.scopeSpans // .instrumentationLibrarySpans // [])[] | .spans[]] | length' \
      "${artifact_dir}/trace-${delivery_trace}.json")" \
  "$(cut -f1 "${artifact_dir}/delivery-trace-spans.tsv" | sort -u | paste -sd, -)" \
  | tee -a "${artifact_dir}/summary.txt"

# 4. API responses expose a correlatable trace ID that exists in Tempo.
curl -fsS -D "${artifact_dir}/api-headers.txt" -o /dev/null "${api}/api/v1/games/game_synthetic_001"
api_trace="$(awk 'tolower($1) == "x-trace-id:" {print $2}' "${artifact_dir}/api-headers.txt" | tr -d '\r')"
[[ "${api_trace}" =~ ^[0-9a-f]{32}$ ]] || { echo 'API did not return X-Trace-ID' >&2; exit 1; }
api_trace_recorded() { trace_spans "${api_trace}" | grep -q $'^courtpulse-api\t'; }
wait_until 'API request trace in Tempo' 60 api_trace_recorded
echo "api_trace_id=${api_trace}" | tee -a "${artifact_dir}/summary.txt"

# 5. Metrics are private: no credential => 401, wrong credential => 401, operator JWT route closed.
[[ "$(curl -s -o /dev/null -w '%{http_code}' "${api}/internal/metrics")" == 401 ]]
[[ "$(curl -s -o /dev/null -w '%{http_code}' -u courtpulse:wrong "${api}/internal/metrics")" == 401 ]]
[[ "$(curl -s -o /dev/null -w '%{http_code}' "${api}/actuator/metrics")" == 401 ]]
curl -fsS -u "courtpulse:${COURTPULSE_METRICS_SCRAPE_PASSWORD}" "${api}/internal/metrics" \
  >"${artifact_dir}/metrics-scrape.txt"
grep -q '^courtpulse_delivery_successes' "${artifact_dir}/metrics-scrape.txt"
grep -q '^courtpulse_worker_heartbeat_age_seconds{worker="delivery"' "${artifact_dir}/metrics-scrape.txt"
grafana_get "${grafana}/api/dashboards/uid/courtpulse-local" | jq -e '.dashboard.panels | length >= 8' >/dev/null

# 6. Telemetry never contains the private destination address or the OIDC subject.
if grep -R -l -e "${private_address}" -e "${private_subject}" \
    "${artifact_dir}"/trace-*.json "${artifact_dir}/metrics-scrape.txt" >/dev/null 2>&1; then
  echo 'Private address or subject leaked into telemetry' >&2
  exit 1
fi

# 7. A real gap blocks reconciliation; the trace crosses correction submit -> reconciliation worker.
worker_run courtpulse-correction reconciliation-worker \
  --correction-fixture=/app/fixtures/corrections/gap-initial.json >"${artifact_dir}/gap-submitted.txt"
wait_until 'gap reconciliation blocked' 60 reconciliation_status_is BLOCKED
wait_until 'correction trace reaching the reconciliation worker' 90 capture_trace \
  courtpulse-reconciliation-worker 'game reconcile' \
  $'courtpulse-correction\tcorrection ingest' \
  $'courtpulse-reconciliation-worker\tgame reconcile'
echo "reconciliation_trace_id=${captured_trace}" | tee -a "${artifact_dir}/summary.txt"
wait_until 'CourtPulseReconciliationBlocked firing' 240 alert_firing CourtPulseReconciliationBlocked

# 8. A stopped worker fires the stale-heartbeat alarm, and recovery resolves it.
"${compose[@]}" stop delivery-worker >"${artifact_dir}/delivery-worker-stop.txt" 2>&1
wait_until 'CourtPulseWorkerStale firing for stopped delivery worker' 300 alert_firing CourtPulseWorkerStale
grafana_get "${prometheus_proxy}/api/v1/alerts" >"${artifact_dir}/alerts-while-failing.json"
"${compose[@]}" start delivery-worker >"${artifact_dir}/delivery-worker-start.txt" 2>&1
wait_until 'CourtPulseWorkerStale resolved after restart' 240 alert_resolved CourtPulseWorkerStale

# 9. New evidence repairs the gap and the blocked alarm clears.
worker_run courtpulse-correction reconciliation-worker \
  --correction-fixture=/app/fixtures/corrections/gap-resolution.json >"${artifact_dir}/gap-resolution.txt"
wait_until 'gap reconciliation completed' 60 reconciliation_status_is COMPLETED
wait_until 'CourtPulseReconciliationBlocked resolved' 240 alert_resolved CourtPulseReconciliationBlocked

# 10. A poison message reaches the FIFO DLQ through real redrive and pages the operator.
"${compose[@]}" exec -T localstack awslocal sqs send-message \
  --queue-url http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/game-events.fifo \
  --message-body '{not-json' --message-group-id obs-poison --message-deduplication-id "poison-${suffix}" \
  >"${artifact_dir}/poison-sent.txt"
worker_run courtpulse-processor delivery-worker --drain >"${artifact_dir}/poison-drain.txt"
dlq_observed() {
  [[ "$(database -c "SELECT visible FROM queue_observations WHERE queue_type='game_events_dlq'")" == 1 ]]
}
wait_until 'reconciliation worker observing one DLQ message' 60 dlq_observed
wait_until 'CourtPulseDlqNonEmpty firing' 180 alert_firing CourtPulseDlqNonEmpty

[[ "$(database -c "SELECT state_version, state_checksum FROM game_checkpoints WHERE game_id='game_synthetic_001'")" \
  == '20|06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca' ]]
[[ "$(database -c "SELECT count(*) FROM delivery_attempts WHERE outcome='SENT'")" == 1 ]]
echo 'Observability verification passed: cross-process traces, private metrics, and four real alerts fired and resolved.' \
  | tee -a "${artifact_dir}/summary.txt"
