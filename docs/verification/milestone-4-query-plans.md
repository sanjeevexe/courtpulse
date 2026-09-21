# Milestone 4 PostgreSQL query-plan evidence

Captured on 2026-09-20 with PostgreSQL 17.6 from the Compose service after Flyway V3 and the
queue-backed synthetic replay. The fixture contained one game, 20 canonical events, one alert, and
41 outbox rows. Commands are read-only and can be reproduced with:

```bash
export COURTPULSE_DB_PASSWORD='courtpulse-local-dev'
docker compose up -d postgres
docker compose exec -T postgres psql -U courtpulse -d courtpulse -P pager=off
```

Each query below used `EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF, SUMMARY OFF)`. The first
plan is PostgreSQL's natural choice for the small fixture. The second was captured inside this
isolated transaction:

```sql
BEGIN;
SET LOCAL enable_seqscan = off;
-- EXPLAIN statements only
ROLLBACK;
```

Disabling sequential scans this way demonstrates index eligibility; it does not predict the normal
production plan and does not change application or database configuration.

## V3 index inventory

```text
idx_game_checkpoints_updated_game (updated_at DESC, game_id)
idx_canonical_events_game_order   (game_id, sequence_number, revision, event_id)
idx_alert_instances_game_order    (game_id, created_at DESC, trigger_key)
idx_outbox_game_group_status      (message_group_id, status, created_at)
  WHERE destination = 'GAME_EVENTS'
```

V3 drops the narrower V1 event `(game_id, sequence_number)` and alert
`(game_id, created_at DESC)` indexes before creating their ordering-complete replacements. The game
list index is distinct from the checkpoint primary key, and the partial outbox index is distinct
from the mutation-oriented pending-work index `(status, next_attempt_at, id)`. No V3 index is a
left-prefix duplicate of another retained index.

## Game list

Representative query:

```sql
SELECT game.id, checkpoint.updated_at
FROM games game
JOIN game_checkpoints checkpoint ON checkpoint.game_id = game.id
ORDER BY checkpoint.updated_at DESC, game.id ASC
LIMIT 21;
```

Natural plan: sequential scans of the two one-row tables followed by a 25 kB quicksort; 8 shared
buffer hits. This is cheaper for one game. Index-eligibility plan:

```text
Limit
  -> Nested Loop
       -> Index Only Scan using idx_game_checkpoints_updated_game (Heap Fetches: 0)
       -> Index Only Scan using games_pkey (Heap Fetches: 0)
```

The V3 index supplies the requested checkpoint order; the game ID provides the deterministic tie
breaker and join key.

## Game snapshot

Representative query:

```sql
SELECT game.id, checkpoint.state_version
FROM games game
JOIN game_checkpoints checkpoint ON checkpoint.game_id = game.id
WHERE game.id = 'game_synthetic_001';
```

Natural plan: two one-row sequential scans, 2 shared buffer hits. The index-only verification used
`games_pkey` for game identity and an index scan for the single checkpoint. Snapshot lookup is
fundamentally supported by the existing primary keys; V3 does not add a redundant snapshot index.
The complete application query also performs one bounded `IN` query for recent event IDs rather
than an N+1 query.

## Canonical event keyset page

Representative query:

```sql
SELECT event_id, sequence_number, revision
FROM canonical_events
WHERE game_id = 'game_synthetic_001'
  AND ROW(sequence_number, revision, event_id) > ROW(7, 1, '')
ORDER BY sequence_number, revision, event_id
LIMIT 4;
```

Natural plan: sequential scan of 20 rows, 14 qualifying rows, top-N heapsort, 8 shared buffer hits.
Index-eligibility plan:

```text
Limit
  -> Index Only Scan using idx_canonical_events_game_order
       Index Cond: game_id = ... AND
         ROW(sequence_number, revision, event_id) > ROW(7, 1, '')
       actual rows=4; Heap Fetches=4
```

The equality prefix and complete tuple ordering match the API cursor exactly, so no offset scan is
required.

## Alert keyset page

Representative query:

```sql
SELECT trigger_key, created_at
FROM alert_instances
WHERE game_id = 'game_synthetic_001'
  AND (created_at < TIMESTAMPTZ '2027-01-01T00:00:00Z'
       OR (created_at = TIMESTAMPTZ '2027-01-01T00:00:00Z' AND trigger_key > ''))
ORDER BY created_at DESC, trigger_key
LIMIT 4;
```

Natural plan: sequential scan and 25 kB quicksort of the single alert, 1 shared buffer hit.
Index-eligibility plan:

```text
Limit
  -> Index Only Scan using idx_alert_instances_game_order
       Index Cond: game_id = 'game_synthetic_001'
       Filter: created_at/trigger_key keyset predicate
       actual rows=1; Heap Fetches=1
```

The index preserves reverse chronological order and makes `trigger_key` the deterministic tie
breaker.

## Failed-outbox blocked-game lookup

Representative query:

```sql
SELECT EXISTS (
  SELECT 1 FROM outbox
  WHERE destination = 'GAME_EVENTS'
    AND message_group_id = 'game_synthetic_001'
    AND status = 'FAILED'
);
```

Natural plan: sequential scan of 41 rows, 2 shared buffer hits. Index-eligibility plan:

```text
Result
  InitPlan
    -> Index Only Scan using idx_outbox_game_group_status
         Index Cond: message_group_id = ... AND status = 'FAILED'
         actual rows=0; Heap Fetches=0
```

The partial predicate excludes deferred-notification rows and supports the per-game
`PROCESSING_BLOCKED` check.

## Processing-health aggregation

The processing endpoint aggregates all 41 outbox rows with filtered counts for pending,
publishing, retry, sent, failed, and deferred records, plus an oldest-eligible timestamp. Its natural
plan is intentionally:

```text
Aggregate (actual rows=1)
  -> Seq Scan on outbox (actual rows=41; shared hit=2)
```

This endpoint asks for whole-table operational totals; forcing an index would not improve that
semantics. At production scale, this diagnostic should be rate-limited and may move to maintained
metrics or rollups rather than accumulating overlapping count indexes.
