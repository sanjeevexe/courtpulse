# ADR 0003 Publish game events through leased outbox rows and SQS FIFO

- Status: Accepted
- Date: 2026-09-20

## Context

PostgreSQL already commits canonical events, game state, durable processed identities, logical
alerts, and publication intent atomically. CourtPulse now needs at-least-once delivery without a
database-plus-broker dual write. Multiple publisher and consumer processes must recover from
crashes while preserving each game's order and allowing unrelated games to progress.

## Decision

Publish only `CANONICAL_EVENT_READY` records whose destination is `GAME_EVENTS` to
`game-events.fifo`. Use the game ID as `MessageGroupId` and the stable outbox deduplication key as
`MessageDeduplicationId`. FIFO serializes delivery within one game while distinct game groups can
progress independently.

FIFO does not replace database idempotency. Broker deduplication is time-bounded, a send can succeed
without database completion, and a consumer can commit without deleting its message. The
`processed_events` identity, checkpoint lock, alert trigger-key uniqueness, and outbox deduplication
key remain the correctness boundary.

### Outbox state, transaction boundary, and leases

```text
PENDING ---------> PUBLISHING ---------> SENT
                         |
                         +-------------> RETRY_SCHEDULED ----> PUBLISHING
                         |
                         +-------------> FAILED
```

A publisher opens a short transaction, selects eligible rows with `FOR UPDATE SKIP LOCKED`, writes
its unique lease owner and expiration, increments the attempt count, and commits. The SQS call occurs
after that commit. Success, retry, or terminal failure is recorded in a second short transaction only
if the same owner still has an unexpired lease. Completion after expiry is rejected even when no
replacement has claimed the row. Expired `PUBLISHING` rows are reclaimable by a new owner.

The claim query permits only `GAME_EVENTS`. `GAME_STATE_UPDATED` and `ALERT_CREATED` have the
explicit `FUTURE_NOTIFICATIONS` destination and remain deferred.

### Ordering across publisher instances

A candidate cannot be claimed while an older non-`SENT` row exists in its game group. This includes
`PENDING`, `RETRY_SCHEDULED`, active or expired `PUBLISHING`, and `FAILED`; terminal failure blocks
later events instead of silently creating a gap. The total order is canonical sequence, revision,
outbox creation time, and outbox UUID. V1's unique `(game_id, sequence_number, revision)` constraint
rejects an ambiguous equal sequence/revision. This predicate is enforced in the claim transaction,
not merely by process-local `ORDER BY`.

Because blocking is scoped by message group, the oldest event for several real games can be leased
in the same batch or by different publisher instances.

### Retry classification and publisher recovery

Network/SDK failures marked retryable by AWS, throttling, HTTP 429, and eligible 5xx SQS responses
are transient. Invalid parameters, missing queues, invalid queue configuration, and other 4xx
responses are terminal. Retry uses bounded exponential backoff with half-to-full jitter; clock and
jitter are injectable. Stored errors normalize whitespace, redact credential-like values, and are
capped at 1,000 characters.

If SQS accepts a send and the publisher crashes before `SENT`, the lease expires and another
publisher republishes. SQS may suppress a near-term duplicate, but consumer idempotency is still
authoritative.

### Envelope validation and acknowledgement

The version-1 envelope carries message type/schema, event/game/sequence, provider
source/ID/revision, occurrence time, outbox ID, stable deduplication key, and optional correlation
ID. Structural validation is followed by comparison with the committed canonical event and outbox
publication identity. Queue routing metadata is never trusted by itself.

Consumers long-poll in bounded batches and call the existing `DurableGameProcessor`. A message is
deleted only after that method returns from its committed transaction. A pre-commit failure leaves
the message unavailable until visibility expires and then redeliverable. If the database commits and
the process fails before delete, redelivery is acknowledged after PostgreSQL suppresses it as already
processed. Graceful close prevents new polls; an in-progress handler completes its explicit
commit/delete boundary.

Local visibility is eight seconds, above expected handler duration. After three failed receives the
source queue redrives to `game-events-dlq.fifo`. Malformed JSON, unsupported schemas, unknown events,
and metadata mismatches remain unacknowledged and reach the DLQ. Logs contain identifiers, counts,
and error classes rather than message bodies or credentials.

## Consequences

- Database and SQS are never written as an unsafe dual write.
- Publisher and consumer crashes can create duplicate delivery but not duplicate state or alerts.
- A failed oldest event intentionally stops only its game; other games keep moving.
- LocalStack supplies real local FIFO, visibility, and DLQ integration coverage.
- Terminal-failure repair and notification-destination publication remain future work.
- REST APIs, WebSockets, UI, authentication, Redis, email, live providers, Terraform, AWS
  deployment, Kubernetes, and full telemetry remain outside this milestone.
