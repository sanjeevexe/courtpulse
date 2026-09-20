# ADR 0001 Keep the initial domain and replay engine infrastructure independent

- Status: Accepted
- Date: 2026-09-19

## Context

CourtPulse ultimately needs provider adapters, durable persistence, queues, HTTP and WebSocket
interfaces, and cloud deployment. The first milestone must instead prove the smallest correctness
boundary: canonical basketball events produce deterministic game state and unique logical alerts
even when a message is delivered more than once.

Coupling this logic to Spring, a database, SQS, or a provider schema would make deterministic tests
slower, obscure the source of correctness, and require infrastructure before the event and state
contracts have stabilized.

## Decision

Keep canonical events, game state, transitions, alert evaluation, duplicate suppression, replay,
and state checksumming in `modules/domain`. The module uses only Java 21 and the JDK.

Provider input is converted at a separate `SourceEventMapper` boundary. The synthetic fixture loader
lives in `modules/providers`, and the CLI performs only orchestration. Event identity is based on the
source, provider event ID, and revision. Logical alert identity is based on a deterministic domain
trigger key rather than a delivery or message ID.

For this milestone, unsupported event types fail explicitly during fixture conversion. Quarantine,
raw-payload retention, persistence-backed uniqueness, corrections, and queue acknowledgment belong
to later milestones because they require infrastructure that does not yet exist.

## Consequences

- Domain and replay tests are fast, deterministic, and require no network or containers.
- A future Spring application can call the same domain logic without owning it.
- Future provider adapters must map their schemas into the canonical contract.
- In-memory duplicate and trigger-key sets prove behavior but are not durable across processes.
- Adding corrections will require an explicit revision and reconstruction policy rather than an
  implicit mutation of this milestone's reducer.
