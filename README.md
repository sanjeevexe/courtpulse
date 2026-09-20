# CourtPulse

CourtPulse is a replay-first basketball event platform. This repository currently implements the
first development milestone: a deterministic, infrastructure-independent domain slice that loads
one synthetic game, calculates game state, evaluates a player milestone rule, and suppresses
duplicate event deliveries and duplicate logical alerts.

The authoritative longer-term direction remains
`CourtPulse_Product_and_Engineering_Plan.docx`. This milestone deliberately stops before databases,
queues, Spring, APIs, authentication, cloud infrastructure, and a web client.

## Prerequisites

- Java 21
- An internet connection for the first Gradle invocation so the wrapper can download Gradle and
  the pinned test and JSON dependencies

No globally installed Gradle, Docker, database, Redis instance, AWS account, or provider credential
is required.

## Build and run

Build every module:

```bash
./gradlew build
```

Run all tests:

```bash
./gradlew test
```

Run the deterministic replay demonstration:

```bash
./gradlew :apps:replay-cli:run
```

Run the same game while redelivering two events, including the milestone-triggering event:

```bash
./gradlew :apps:replay-cli:run --args='--inject-duplicates'
```

The two runs must print the same final-state checksum, final score, selected-player point total, and
logical alert set. The duplicate run additionally prints two suppressed duplicate deliveries.

## Current architecture

```text
apps/replay-cli
    -> modules/testkit      synthetic, redistributable fixture
    -> modules/providers    fixture parsing and provider-neutral mapping boundary
    -> modules/domain       canonical events, pure state transitions, rules, replay
```

### `modules/domain`

Contains only Java domain types and JDK APIs. It owns:

- The version 1 immutable canonical event contract
- Provider-scoped event identity using source, provider event ID, and revision
- Game status, score, clock, period, selected player totals, sequence, and recent history
- Strict in-sequence application of previously unseen events
- Pure game-state transitions
- `PLAYER_MILESTONE` crossing evaluation
- Deterministic trigger keys and logical-alert suppression
- SHA-256 final-state checksums

It has no dependency on Spring, Jackson, a database, a queue, AWS, or the system clock.

### `modules/providers`

Defines the conversion boundary that future provider adapters must implement. The only current
adapter reads the CourtPulse fixture format, rejects unknown fields, validates required canonical
fields, and converts fixture records into domain events. Unsupported event types fail explicitly at
this boundary; quarantine storage is deferred until persistence exists.

### `modules/testkit`

Packages the synthetic four-period game used by tests and the CLI. The fixture ends 18–14 and gives
`player_ace` 13 points. The 10-point milestone is crossed once. See
[`fixtures/README.md`](fixtures/README.md) for provenance.

### `apps/replay-cli`

Loads the fixture, optionally injects duplicate deliveries, runs the pure replay engine, and prints
the final state, logical alerts, accepted and suppressed counts, and checksum.

## Correctness boundaries

Event and alert deduplication solve different problems:

- An event is considered already applied when its provider-scoped source, provider event ID, and
  revision have already been accepted.
- An alert is considered already created when its deterministic domain trigger key has already been
  emitted. The milestone key is
  `PLAYER_MILESTONE:{ruleId}:{gameId}:POINTS:{threshold}`.

Duplicate detection happens before sequence validation, so a redelivered older message is safely
acknowledged in memory. A previously unseen event must be exactly the next sequence or replay fails
with a clear sequence error.

The checksum covers durable game facts, sorted player totals, accepted event identities, and recent
event history. Operational counters such as suppressed duplicate deliveries are intentionally not
part of game state, so duplicate redelivery does not alter the checksum.

## Test coverage

The automated suites verify:

- Repeatable checksums and logical alert sets
- The expected 18–14 final score and 13-point selected-player total
- Strict sequence application
- State invariance under duplicate event delivery
- One logical milestone alert when its triggering event is delivered twice
- Threshold-crossing rather than repeated at-or-above-threshold behavior
- Clear malformed-fixture failure
- Explicit unsupported-event failure
- CLI evidence for the duplicate-injection path

All tests run locally in memory without Docker or internet access after Gradle dependencies have
been cached.

## Decisions and limitations

The initial architecture decision is recorded in
[`docs/adr/0001-infrastructure-independent-domain.md`](docs/adr/0001-infrastructure-independent-domain.md).

This milestone intentionally supports only regulation periods and five event types:
`GAME_STARTED`, `PERIOD_STARTED`, `FIELD_GOAL_MADE`, `FREE_THROW_MADE`, and `GAME_FINAL`.
It does not yet support overtime, corrections, checkpoint reconstruction, persistence, live data,
or concurrent processing.
