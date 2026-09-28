# Local observability and incident response

Start the isolated telemetry project with `scripts/start-observability.sh`. It generates
ignored local credentials in `build/observability`, builds images that contain the
checksum-pinned Java agent, and uses `courtpulse-observability` volumes, not the ordinary
Compose project's volumes.
Grafana is at `http://127.0.0.1:13000` (user `courtpulse`; read its generated password from
`build/observability/grafana-password`). Prometheus and Tempo have no host ports. Use
`scripts/observability-compose.sh ps` and `scripts/observability-compose.sh logs SERVICE`
for inspection. Stop with `scripts/observability-compose.sh down` to keep its data. Never
run `down -v` on the normal Compose project while following this runbook.

`/internal/metrics` returns 404 when the scrape secret is unset and 401 without the
generated credential. `/actuator/metrics` still requires an operations JWT; `/actuator/env`
remains denied. A 401 from the scrape endpoint is a credential or provisioning failure, not
an empty metric stream. Check Prometheus target state before trusting dashboard panels.

## API or scrape unavailable

Check API readiness and the Prometheus target. Distinguish an API crash, database failure,
and scrape authentication failure. Inspect container health and redacted logs; do not paste
tokens or raw trace payloads into an incident ticket. Restore the failed dependency before
restarting the API. If the app is healthy but only the scrape fails, verify that the
`build/observability/metrics-password` file and API environment refer to the same generated
credential.

## Stale feed

`CourtPulseLiveFeedStale` applies only while a game is marked live. Check the source ingest
process and the last observed raw-payload timestamp. A historical fixture with a final
game is not a live-provider outage. Do not fabricate plays to clear the alarm.

## Event or outbox lag

Compare oldest unprocessed event age, outbox backlog and age, queue observation age, and
worker heartbeat. If a game is blocked, follow the reconciliation runbook rather than
deleting its older outbox row. Review a failed lease or queue permission error before
replaying. Preserve the original idempotency keys.

## Dead-letter queue

Confirm that the queue observation is fresh and identify the affected queue. Inspect only
safe message metadata and receive count; keep bodies and private delivery IDs out of shared
logs. For malformed messages, prove actual redrive using source/DLQ visible and in-flight
counts. For a valid failed event, repair its cause before any redrive, then confirm one
checkpoint and one logical alert. Never manually move a poison message merely to silence
the alarm.

## Blocked reconciliation

Inspect `game_reconciliations.last_error_code`, gap sequence, and immutable attempt history
with a read-only database session. Follow [the reconciliation runbook](reconciliation.md).
Only new valid provider evidence may unblock a gap or conflict. A repeated fixture with the
same bytes is not a repair.

## Delivery failures

Compare delivery backlog age, retries, terminal failures, lease recoveries, and DLQ depth.
Use owner-scoped delivery history for user support and aggregate operations for monitoring;
do not expose addresses or rule parameters in telemetry. An `UNKNOWN_ACCEPTANCE` attempt
means SMTP may already have accepted the email. Do not blindly resend it. For a corrected
alert, retain the old sent attempt and cancel only pending work.

## Worker stale

Check the worker's Compose health, heartbeat timestamp, last successful pass, queue
observation age, and logs. A stale heartbeat with rising queue age indicates real lost
processing; a fresh heartbeat with stale queue observations points to SQS observation
failure. Restart only the affected worker after recording its state. Its leases and
idempotency constraints provide recovery, but verify that backlog drains afterward.

## Provider outage or throttling

`CourtPulseProviderCircuitOpen` means the live client stopped calling the provider after five
consecutive failures; it probes again after 60 seconds. Read `GET /api/v1/operations/providers`
with an operations token: `lastErrorCode` distinguishes `http_429` (quota), `http_401`/`http_403`
(wrong key or a tier without play-by-play), `http_5xx`/`timeout` (provider trouble), and
`rate_limited_local` (CourtPulse is waiting out a `Retry-After`). Live games turn `STALE` in the
UI while polls fail; that is correct behavior, not an incident to hide. Do not raise
`COURTPULSE_PROVIDER_REQUESTS_PER_MINUTE` above the purchased tier to "catch up": the ingestor
refetches whole games, so nothing is lost by waiting. For 401/403, rotate or re-enter the key
(`scripts/aws/set-provider-key.sh` in AWS) and confirm the tier includes play-by-play.

## Provider data incident

`CourtPulseProviderDataIncident` fires when provider evidence could not be accepted as-is. Inspect
`provider_data_incidents` with a read-only session (`reason`, `detail`, `provider_event_id`; the
bounded `payload` is licensed data, so do not paste it into tickets). `MAPPING_REJECTED` blocks the
game at that sequence until the provider fixes the play; check the `detail` code against ADR 0013's
mapping rules before changing any mapper. `REVERTED_CONTENT` means the provider went back to an
older body; decide with the reconciliation runbook whether a new revision is warranted.
`PLAY_REMOVED` means an identity vanished from the feed; CourtPulse keeps the stored evidence and
state until an operator decides otherwise.

## Controlled verification

Run `scripts/verify-observability.sh` for an isolated gap-and-worker failure exercise.
The script captures pass/fail evidence under `build/verification/observability`, restores
the worker, and cleans only its own uniquely named Compose project. A firing alert and a
cross-process trace are required; container health alone is insufficient.
