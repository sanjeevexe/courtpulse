# ADR 0004 Expose durable state through a versioned read API

- Status: Accepted
- Date: 2026-09-20

## Context

CourtPulse durably records games, canonical events, checkpoints, logical alerts, processed-event
identities, and outbox state in PostgreSQL. Clients need a stable read contract without coupling
HTTP representations to mutation repositories, queue messages, provider payloads, or domain
implementation classes. The existing persistence path is blocking JDBC.

Authentication and an external operations perimeter are deferred. The first API must therefore be
read-only, intentionally narrow, safe to operate locally, and explicit about incomplete or delayed
data.

## Decision

Create `modules/query` for read models, query orchestration, opaque keyset cursors, data-status
policy, and purpose-built parameterized JDBC. Create `apps/api` as the Spring Boot composition root
for MVC controllers, public DTOs, errors, OpenAPI, correlation, Actuator, and runtime configuration.
`modules/domain` remains infrastructure-free, public REST DTOs remain outside it, and JDBC rows do
not escape the read repository.

Spring MVC is used because request processing calls blocking JDBC. WebFlux would add a reactive
surface without making the database access non-blocking.

### Source of truth and versioning

PostgreSQL is the only read source. The API does not inspect SQS or require LocalStack. Public
resources live below `/api/v1`; breaking representation or semantic changes require a new API
version. The checked-in OpenAPI 3.1 contract is compared with the runtime document in an automated
test.

### Stable pagination

Lists are bounded to 100 items. Games order by checkpoint update time descending then game ID;
events order by sequence, revision, then event ID; alerts order by creation time descending then
trigger key. A URL-safe Base64 JSON cursor contains a cursor version, resource type, resource scope,
and the last ordering keys. It contains no SQL. Decoding rejects malformed, cross-resource, and
cross-scope cursors. Inserts after a returned key cannot repeat items already returned.

### Caching and errors

A game snapshot ETag hashes game ID, checkpoint version, and state checksum. A matching
`If-None-Match` returns 304 without a representation body; a processed event changes the checkpoint
version and therefore the ETag. No long-lived cache policy is asserted for live games.

Errors use `application/problem+json` with type, title, status, safe detail, request instance,
correlation ID, and stable application code. Database failures return a sanitized 503. SQL,
credentials, raw payloads, internal addresses, exception names, and stack traces are excluded from
responses.

### Data status

`dataStatus` is an API/query concern separate from the basketball `GameStatus` enum. A failed
game-event outbox record takes precedence and yields `PROCESSING_BLOCKED`. Otherwise scheduled and
final games yield `SCHEDULED` and `FINAL`. A live checkpoint updated within the configured freshness
window yields `LIVE`; an older live checkpoint yields `STALE`. The policy receives an injected
`Clock` for deterministic tests.

### Health and operations

Actuator exposes only health, liveness, readiness, info, and metrics. Liveness includes application
liveness state only and remains up when PostgreSQL is unavailable. Readiness includes PostgreSQL
connectivity and a schema check for the six required tables. Health details, environment, beans,
configuration properties, and heap dumps are not exposed.

`/api/v1/operations/processing` reports bounded aggregate counts, oldest eligible age, blocked-game
count, and API time. It never returns outbox error text or payloads. This endpoint requires access
control before any public deployment.

### Query plans and indexes

Flyway V3 replaces narrower event and alert indexes with ordering-complete indexes and adds indexes
for the game-list and failed-outbox lookups:

- `idx_canonical_events_game_order (game_id, sequence_number, revision, event_id)`
- `idx_game_checkpoints_updated_game (updated_at DESC, game_id)`
- `idx_alert_instances_game_order (game_id, created_at DESC, trigger_key)`
- partial `idx_outbox_game_group_status (message_group_id, status, created_at)` for `GAME_EVENTS`

These match equality prefixes and keyset orderings. PostgreSQL may reasonably choose sequential
scans for the tiny 20-event fixture; forced index-plan verification confirms the intended access
paths while normal `EXPLAIN ANALYZE` records the representative small-data choice. The reproducible
commands and captured plan nodes are recorded in
[`docs/verification/milestone-4-query-plans.md`](../verification/milestone-4-query-plans.md).

### Container boundary

The API image uses pinned Gradle/JDK 21 and JRE 21 Alpine stages. Only the reproducible Boot JAR is
copied to the final non-root image. Compose makes the API depend only on healthy PostgreSQL; the read
service has no LocalStack dependency.

## Consequences

- HTTP clients are insulated from database, queue, and domain implementation types.
- Durable state survives API restarts and supports conditional reads.
- Pagination is deterministic without increasingly expensive offsets.
- Database outage affects readiness and reads, but not liveness.
- The unauthenticated operations endpoint must remain narrow and must be protected before public
  exposure.
- User resources, write APIs, streaming, provider polling, corrections, Redis, email, live cloud
  deployment, Terraform, and Kubernetes remain deferred.
