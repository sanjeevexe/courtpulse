# CourtPulse

CourtPulse is a replay-first basketball event platform. The repository now contains two executable
vertical slices:

- An infrastructure-free replay that proves deterministic event reduction and alert evaluation.
- A durable PostgreSQL replay that stores source and canonical events, processes them in sequence,
  checkpoints game state, suppresses duplicates across restarts, and writes a transactional outbox.

The product direction is documented in `CourtPulse_Product_and_Engineering_Plan.docx`. This
milestone intentionally stops before HTTP APIs, React, WebSockets, Redis, queues, authentication,
email, live providers, and cloud infrastructure.

## Prerequisites

- Java 21
- Docker with Docker Compose for the manual PostgreSQL demonstration
- Internet access on the first build so Gradle can download its pinned distribution and dependencies

No global Gradle or PostgreSQL installation is required. Tests use Testcontainers when Docker is
available; a real embedded PostgreSQL suite also verifies the durable path on development machines
without Docker.

## Build and test

```bash
./gradlew clean build
```

The build runs the infrastructure-free tests, domain invariant tests, embedded PostgreSQL tests,
the Spring Boot command test, and Testcontainers tests. Tests annotated for Testcontainers are
reported as skipped when Docker is unavailable.

Run only the Testcontainers PostgreSQL suite:

```bash
./gradlew :modules:persistence:test --tests '*DurablePostgresIntegrationTest'
```

## Infrastructure-free replay

```bash
./gradlew :apps:replay-cli:run
./gradlew :apps:replay-cli:run --args='--inject-duplicates'
```

Both commands produce an 18–14 final score, 13 points for `player_ace`, one logical milestone
alert, and this checksum:

```text
06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca
```

## Durable PostgreSQL replay

Choose a local-only password in your shell, start PostgreSQL, and check its health:

```bash
export COURTPULSE_DB_PASSWORD='choose-a-local-password'
docker compose up -d postgres
docker compose ps
```

The application defaults to `jdbc:postgresql://localhost:5432/courtpulse` with user `courtpulse`.
Override `COURTPULSE_DB_URL` or `COURTPULSE_DB_USERNAME` when needed. No password or generated
database data is committed; Compose stores data in the `courtpulse-postgres-data` volume.

Reset the demonstration database, import the fixture, and process it:

```bash
./gradlew :apps:durable-replay-cli:run --args='--reset'
```

Reset and inject two duplicate deliveries during processing:

```bash
./gradlew :apps:durable-replay-cli:run --args='--reset --inject-duplicates'
```

Process the already imported canonical events in a new application process without importing them
again:

```bash
./gradlew :apps:durable-replay-cli:run --args='--reprocess-existing'
```

A normal invocation without `--reset` idempotently observes/imports the fixture again and attempts
processing:

```bash
./gradlew :apps:durable-replay-cli:run
```

Each run prints the durable final state, alerts, accepted and suppressed event counts, import counts,
checksum, and all relevant table counts. Stop PostgreSQL with `docker compose down`; add `-v` only
when you intentionally want to delete the local database volume.

## Module structure

```text
apps/replay-cli
    -> modules/testkit
    -> modules/providers
    -> modules/domain

apps/durable-replay-cli
    -> modules/persistence
    -> modules/testkit
    -> modules/providers
    -> modules/domain

compose.yaml -> PostgreSQL only
```

- `modules/domain` contains canonical events, immutable game state, pure transitions, rules, and
  checksumming. It has no Spring, JDBC, PostgreSQL, queue, or system-clock dependency.
- `modules/providers` validates and maps source events. Its fixture loader retains a stable raw JSON
  representation and SHA-256 content hash alongside each canonical event.
- `modules/testkit` owns the redistributable four-period synthetic fixture.
- `modules/persistence` owns explicit SQL repositories, transaction-aware services, Flyway
  migrations, checkpoint reconstruction, durable deduplication, alerts, and outbox records.
- `apps/replay-cli` preserves the original in-memory demonstration.
- `apps/durable-replay-cli` is the Spring Boot composition root and command-line orchestrator.

## Schema and migrations

Flyway automatically applies migrations from `modules/persistence/src/main/resources/db/migration`
when the durable application starts. `V1__durable_event_processing.sql` creates:

- `games`
- `raw_provider_payloads`
- `canonical_events`
- `game_checkpoints`
- `processed_events`
- `alert_instances`
- `outbox`
- `replay_runs`

The schema enforces provider identity plus revision, game sequence plus revision, processed identity
per consumer, one checkpoint per game, unique alert rule and trigger key, and unique outbox
deduplication keys. Queryable identity, status, sequence, and time fields use typed columns; variable
payloads and compact state collections use JSONB.

## Transaction and idempotency boundaries

Fixture ingestion is one transaction. For each source event it stores or observes the raw payload,
stores its canonical form, and writes `CANONICAL_EVENT_READY` to the outbox. Re-importing identical
content updates only observation metadata and creates no second canonical event or outbox record.

Each game event is processed in a separate transaction. The processor loads and locks the game's
checkpoint, validates game and score invariants before duplicate suppression, checks the durable
consumer identity, applies the pure reducer, updates the checkpoint, records the processed event,
creates any unique logical alert, and writes state/alert outbox records. A pre-commit failure rolls
all those writes back. A failure after commit is safe because redelivery finds the durable processed
identity and leaves state, alerts, and outbox unchanged.

PostgreSQL constraints are the final race-safety boundary. The checkpoint row lock serializes
competing deliveries for a game, while processed-event, trigger-key, and outbox uniqueness protects
against duplicate writes.

The outbox currently remains in PostgreSQL for inspection. A future publisher can claim pending
rows, publish them to SQS, and mark them sent. Since publication may happen more than once, every
record has a stable deduplication key and downstream consumers must remain idempotent.

## Domain correctness

The domain now identifies home and away teams and rejects score decreases, score changes on both
sides, point deltas that disagree with the scoring event, scoring-team/side mismatches, and score
changes on non-scoring events. Wrong-game events and malformed duplicate deliveries are validated
before any duplicate is acknowledged.

The final-state checksum uses representation version 2. It covers game ID, home and away team IDs,
status, period, game clock, score, last applied sequence, sorted player totals, every accepted
provider identity with its canonical-event fingerprint, and the bounded recent-event history.
Operational timestamps, replay-run counters, and suppressed-delivery counts are deliberately
excluded, so restarts and duplicate redelivery do not alter it. Version 2 changed the golden
checksum because version 1 omitted team identity and retained only accepted identities rather than
fingerprints. The synthetic game's version 2 checksum is the value shown above.

Every accepted provider identity retains a compact SHA-256 fingerprint of the complete canonical
event. This allows a conflicting redelivery to be rejected even after the original full event has
fallen outside the ten-event recent-history window.

## Current limitations

- The synthetic fixture is the only provider input; no live or licensed data is included.
- Regulation periods and five event types are supported; overtime and corrections are deferred.
- There is no outbox publisher or retry worker yet—outbox rows remain `PENDING`.
- Processing is command-driven rather than queue-driven.
- There are no REST endpoints, client application, streaming updates, authentication, email, or
  cloud deployment.
- `--reset` is a destructive local demonstration command and should not become a production
  administrative interface.

Architecture decisions are recorded in
[`docs/adr/0001-infrastructure-independent-domain.md`](docs/adr/0001-infrastructure-independent-domain.md)
and [`docs/adr/0002-postgresql-transactional-outbox.md`](docs/adr/0002-postgresql-transactional-outbox.md).
