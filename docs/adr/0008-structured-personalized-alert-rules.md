# ADR 0008 Use structured personalized alert rules

- Status: Accepted
- Date: 2026-09-21

## Context

CourtPulse needs user-defined alerts without turning an event processor into a code-execution
service. Arbitrary expressions, scripts, SQL, or predicates would make validation, indexing,
resource bounds, privacy review, and deterministic replay impractical. Rules also need to coexist
with the original public `player_ace` ten-point demo alert on clean and upgraded databases.

## Decision

Support exactly three versioned, structured templates. Their parameters are typed and bounded at the
HTTP, domain, and database boundaries.

- `PLAYER_POINTS` enters when the previous verified player total is below the threshold and the next
  verified total is at or above it. Remaining above does not fire again. Its logical trigger identity
  is rule, game, statistic, and threshold, so a later correction and recross remains the same logical
  alert.
- `CLOSE_GAME` enters when the next state is `LIVE`, its period exactly equals `eligiblePeriod`, its
  remaining clock is less than or equal to `maximumClockMillisRemaining`, and its absolute score
  margin is less than or equal to `maximumMargin`, while the previous state did not meet all four
  conditions. Exit is silent. Re-entry is a new alert identified by the canonical event ID.
- `SCORING_RUN` uses an explicit durable aggregate: points scored by one team after the most recent opponent
  scoring event. Made field goals and free throws add their canonical point value, non-scoring events
  preserve the run, and an opponent score terminates it. Only the transition from below the threshold
  to at-or-above fires. The rule ID plus run-start sequence identifies one logical run, so later points
  in that run cannot duplicate it and a later run remains distinct.

The processor advances one `game_scoring_runs` row on every scoring event, whether or not a rule
currently exists. V6 reconstructs the row once from prior processed history during upgrade. Runtime
evaluation therefore uses a constant-size read and update; it never scans a growing scoring history
for every event. The accumulator commits or rolls back with the checkpoint and alert.

Evaluators live in `modules/domain`. They consume only the previous verified state, next verified
state, canonical event, and bounded `ScoringRunFacts`. They have no database, framework, clock,
network, or expression-engine dependency. Trigger context stores the verified threshold, totals,
margin, clock, period, team/player, and run start needed to explain the alert later; subsequent rule
edits cannot rewrite this snapshot.

Flyway V6 stores typed columns rather than JSON predicates. Partial indexes separately serve enabled
player, team, and close-game candidates. Owner rules and owner alerts use reverse-created-time
keyset indexes. Processing looks up only enabled candidates for the current game and event facts;
there is no full rule-table evaluation. The original system rule has a null owner, a deterministic
database ID, and its historical `milestone-player-ace-10` domain identity so its trigger key remains
stable. V6 backfills that rule for every existing game, while fixture import creates it for future
games idempotently.

The API admits at most 50 owned rules per user and 1,000 owned rules per game, counting disabled
rules. Creation locks the game row before checking its count. The event lookup has an additional
1,001-candidate ceiling, including the system rule, and fails closed if direct database writes
violate that invariant.

Rule lookup occurs inside the same game transaction, after the checkpoint row is locked and before
the checkpoint, processed identity, alert, and applicable public outbox rows commit. Candidate rows
are read `FOR SHARE`. Enable/disable and delete require a conflicting row lock: if the mutation
commits first, the event does not use the rule; if processing locks first, that event finishes with
the observed rule and the mutation follows. Rule deletion intentionally does not erase immutable
alert history. User deletion cascades all of that user's rules and private alerts.

Creation requires a bounded safe idempotency key. A unique owner/key index serializes concurrent
retries, and an immutable SHA-256 fingerprint of the normalized original request detects key reuse
with another body even if the rule has since been enabled or disabled. Owner-row locking makes the
50-rule user quota and 1,000-rule game quota transactional. Updates use a monotonically increasing version and reject stale
writes.

Ownership always comes from the validated JWT `sub`; requests never contain an owner. Owner-scoped
queries return the same 404 for an absent or foreign UUID. Public game-alert reads filter to null
owners, and personalized alerts never create public `ALERT_CREATED` WebSocket outbox rows. Metrics
use only bounded rule type and outcome tags. The protected operations endpoint exposes aggregate
counts, not subjects, rule parameters, destinations, or trigger identities.

## Consequences

- Rule behavior is replayable, indexable, resource-bounded, and reviewable.
- Queue redelivery is harmless because processed-event and trigger-key uniqueness remain durable.
- A rule mutation concurrent with processing has explicit lock-order semantics rather than a race
  against an out-of-transaction cache.
- PostgreSQL remains the source of truth for both public and private history.
- Adding a fourth template requires an explicit domain type, migration shape, indexes, contract,
  UI, tests, and privacy review.
- User-specific realtime transport and external delivery are not part of this decision. In-app
  history is available through authenticated HTTP; reliable local email delivery is deferred to the
  next milestone.
