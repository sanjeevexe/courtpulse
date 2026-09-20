# ADR 0002 Use PostgreSQL as the durable correctness boundary

- Status: Accepted
- Date: 2026-09-20

## Context

The infrastructure-free replay proves that canonical events reduce deterministically and that
logical alerts have stable identities. Its in-memory sets and checkpoint cannot, however, survive a
process restart or protect against concurrent duplicate deliveries. CourtPulse needs a durable
boundary before adding network APIs or an at-least-once queue.

This milestone must retain raw provider evidence, canonical events, the current game checkpoint,
processed identities, alerts, and messages intended for future publication. State and publication
intent must never diverge when a process fails.

## Decision

Use PostgreSQL as the durable correctness boundary. Store frequently queried identity, sequence,
status, and timestamp fields in typed columns, reserving JSONB for source/canonical payloads and
variable state or alert context. Enforce identity and logical uniqueness in the schema in addition
to application checks.

Use Spring `JdbcClient`, explicit SQL, and `TransactionTemplate`. This keeps row locking, insert
conflict behavior, and transaction scope visible. JPA is not introduced because the milestone is
primarily an event-processing and idempotency exercise rather than an object-relational aggregate
model.

Treat fixture ingestion as one transaction. Treat each canonical event as one game-processing
transaction. The processing transaction locks the checkpoint row, validates and reduces the event,
persists the checkpoint and processed identity, creates any logical alert, and writes all resulting
outbox records before committing.

Use a transactional outbox instead of publishing directly. Every outbox record has a stable unique
deduplication key and explicit delivery status, attempt count, and retry time. The current milestone
does not publish these records.

Defer SQS until a later milestone. Adding it now would combine database correctness with broker
configuration and delivery semantics before the durable state boundary is proven. A future worker
will claim pending outbox rows, publish them at least once, and update their status; consumers must
deduplicate using the stable identity.

## Consequences

- Committed checkpoints, processed identities, alerts, and outbox intent survive process restarts.
- Database constraints and checkpoint row locks provide a final boundary under concurrent duplicate
  attempts.
- A failure before commit leaves no partial processing state; a failure after commit is recovered by
  idempotent redelivery.
- Flyway owns repeatable schema creation from an empty PostgreSQL database.
- Persistence remains outside `modules/domain`, preserving fast deterministic unit tests.
- PostgreSQL is now required for the durable demonstration and production-like integration tests.
- Outbox backlog, claiming, retries, publication, and cleanup remain work for a later milestone.
