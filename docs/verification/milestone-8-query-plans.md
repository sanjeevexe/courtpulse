# Milestone 8 personalized-rule query plans

These plans were verified on PostgreSQL 17.6 on 2026-09-21. The executable assertion is
`PersonalizedRulesIntegrationTest.primaryAndOwnerHistoryQueriesUseTheirBoundedIndexes`. It runs
`EXPLAIN (COSTS OFF)` on the migrated schema with `enable_seqscan=off`, which is appropriate for
proving index eligibility on the deliberately tiny deterministic fixture. Production planning may
choose a sequential scan for a tiny table; the indexes become naturally preferable as cardinality
grows.

## Enabled candidates for one event

The processor binds one game plus the current period, clock, margin, player, and team:

```sql
EXPLAIN (COSTS OFF)
SELECT id
FROM alert_rules
WHERE enabled AND game_id = 'game_synthetic_001' AND (
  (rule_type = 'CLOSE_GAME' AND eligible_period = 4
    AND maximum_clock_millis_remaining >= 1000 AND maximum_margin >= 2)
  OR (rule_type = 'PLAYER_POINTS' AND player_id = 'player_ace')
  OR (rule_type = 'SCORING_RUN' AND team_id = 'team_home'))
ORDER BY id;
```

The runtime query also applies `LIMIT 1002 FOR SHARE`; a 1,002nd candidate violates the
1,001-candidate ceiling and rolls the event transaction back. The API prevents that state by
serializing creation under the game row and admitting at most 1,000 owned rules per game.

The plan is a bitmap heap scan bounded to that game with a `BitmapOr` over:

```text
idx_alert_rules_game_close
idx_alert_rules_game_player
idx_alert_rules_game_team
```

All three are partial indexes with `WHERE enabled` and a fixed rule type. Disabled rows are not
eligible for evaluation. The separate disabled count uses the same event predicates only for a
bounded operational metric; it does not instantiate or evaluate rules.

## Owner rule history

```sql
EXPLAIN (COSTS OFF)
SELECT id
FROM alert_rules
WHERE owner_subject = 'plan-user'
ORDER BY created_at DESC, id ASC
LIMIT 21;
```

The plan uses `idx_alert_rules_owner_order`, whose keys exactly match the owner and keyset order.
The API fetches at most requested limit plus one, with a maximum public limit of 100.

## Owner alert history

```sql
EXPLAIN (COSTS OFF)
SELECT id
FROM alert_instances
WHERE owner_subject = 'plan-user'
ORDER BY created_at DESC, id ASC
LIMIT 21;
```

The plan uses `idx_alert_instances_owner_order`. It is partial on non-null owners, so public system
alerts do not enlarge the private-history index. Opaque cursors carry only the last creation time and
UUID and are cryptographically scoped by resource type and owner.

## Scoring-run cost

`game_scoring_runs` has one primary-key row per game. Every scoring event reads and updates only
that row inside the checkpoint transaction. V6 performs a one-time history reconstruction for
upgraded games; subsequent processing has no historical aggregate query. The separate existing
checksum path still reads prior processed identities, so overall reducer cost is not claimed to be
constant with game length.

To reproduce just this evidence:

```bash
./gradlew :apps:api:test \
  --tests 'com.courtpulse.api.PersonalizedRulesIntegrationTest.primaryAndOwnerHistoryQueriesUseTheirBoundedIndexes' \
  --rerun-tasks --console=plain
```
