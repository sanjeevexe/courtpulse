# ADR 0014: Batch outbox publication and never let a transient failure wedge a game

- Status: Accepted (measured locally; see the performance and recovery reports)
- Date: 2026-09-28
- Amends: [ADR 0003](0003-sqs-fifo-outbox-leasing.md)

## Context

The plan's stress target is 50 concurrent games and a 200 events per second burst, with
processing delay (canonical commit to checkpoint commit) below 500 ms at p95. The first
performance run drained a 2,000-event burst at 72 to 99 events per second. A diagnostic split the
time: the publisher needed 20.2 s to publish what consumers processed at a published-to-processed
p50 of 2 ms. The publisher claimed at most 10 rows per cycle and called `SendMessage` once per row.

Recovery drills then found five ways a short, harmless infrastructure event could permanently
stop a game. An older `FAILED` row blocks its game by design (ADR 0003) and no automated repair
exists, so each of these mattered:

1. The SQS failure classifier treated an unreachable endpoint (an `SdkClientException` caused by
   `UnknownHostException` or `ConnectException`) as permanent.
2. Transient failures became terminal after five attempts (about 7 to 15 s of jittered backoff).
3. Shutdown interrupted workers immediately. A publisher interrupted mid-send got
   `AbortedException`, which was classified as permanent, so an ordinary SIGTERM (every ECS
   deployment and scale-in) could mark a row `FAILED`.
4. A failed queue-depth read, which is only telemetry, crashed the whole processor, and consumers
   gave up after five consecutive failures (about 10 s), so any SQS blip became a restart loop.
   Each restart then left the crashed publisher's leases to expire (30 s).
5. SQS calls had no per-attempt timeout. On a silently dropped connection a send waited out the
   30 s socket timeout, stalling all publication.

## Decision

**Batching.** One claim takes up to 50 rows (`COURTPULSE_PUBLISHER_BATCH_SIZE`). The claim query
already admits only the oldest unsent row of each game, so a claim never holds two rows of one
FIFO message group. The publisher sends the claim as `SendMessageBatch` calls of ten entries,
concurrently on virtual threads, and settles every entry on its own: successes are marked `SENT`
in one transaction and each failed entry follows the retry policy. Batching therefore cannot
reorder a game. A single-row claim and the crash-simulation modes keep the one-message path. Each
record still gets its own producer span, started from its stored `traceparent`.

**Classification.** Timeouts, aborted calls, and client exceptions caused by an `IOException` are
transient, like throttling and 5xx responses. Credential, endpoint-configuration, and 4xx request
errors remain permanent.

**Retry budget.** `COURTPULSE_PUBLISHER_MAX_ATTEMPTS` defaults to `0`: transient failures retry
until they succeed with capped, jittered exponential backoff (1 s initial, 30 s maximum). Only
permanent failures become `FAILED`. A positive value restores a bounded budget.

**Bounded calls.** Every SQS send, delete, and attribute read has a 5 s attempt timeout; receives
get their long-poll wait plus 5 s. A dropped connection becomes a retryable timeout.

**Riding out outages.** Worker steps back off (capped at 10 s) and restart the process only after
two minutes of continuous failure, so a restart still surfaces a lasting fault such as revoked
credentials. Queue-depth telemetry failures are logged and skipped. On shutdown, workers get a
15 s grace period to finish their current step before stragglers are interrupted; ECS allows 30 s
between SIGTERM and SIGKILL.

## Consequences

- Sustained 200 events per second across 50 games: p95 processing delay under 100 ms. Saturation
  drain rose from 99 to about 455 events per second (`docs/verification/performance-report.md`).
- A processor SIGKILL, a PostgreSQL restart, a 20 s SQS partition, and a SIGTERM mid-publication
  all recover without an operator and without duplicate state or alerts
  (`docs/verification/recovery-report.md`).
- A permanently misconfigured queue still fails fast and visibly.
- An entry that SQS accepted but whose response was lost is republished after its lease expires;
  FIFO deduplication and consumer idempotency absorb the duplicate, as before.
- A long outage shows up as outbox and queue age alarms rather than `FAILED` rows.
- Terminal-failure repair tooling remains future work; it is now needed only for permanent errors.
