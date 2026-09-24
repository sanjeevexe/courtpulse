# Reconciliation operations

Read `GET /api/v1/operations/reconciliations` with a bearer token carrying the configured
`courtpulse:ops` authority. It reports aggregate pending, rebuilding, blocked, completed,
unchanged, failed-attempt, and oldest-pending counts. The public game snapshot marks a blocked
game `PROCESSING_BLOCKED`; it does not reveal owner or rule information.

For a local game, inspect the immutable evidence with a read-only database connection:

```sql
SELECT status, generation, first_affected_sequence, gap_sequence, last_error_code,
       requested_at, updated_at
FROM game_reconciliations WHERE game_id = 'game_synthetic_001';
SELECT generation, status, selected_events, gap_sequence, error_code
FROM reconciliation_attempts WHERE game_id = 'game_synthetic_001'
ORDER BY generation;
SELECT sequence_number, revision, event_id, raw_payload_id
FROM reconciliation_selected_events WHERE game_id = 'game_synthetic_001'
ORDER BY generation, sequence_number;
```

`sequence_gap` means the first missing sequence is in `gap_sequence`. Acquire the missing
provider observation or a trustworthy local fixture, then submit it via
`--correction-fixture=<path>`. A new accepted observation moves `BLOCKED` to `PENDING`; an
identical redelivery does not. `ambiguous_revision` means two source observations claimed the
same revision. Inspect `reconciliation_conflict_observations` privately and require a newer
unambiguous provider revision. `invalid_event` means no revision can validly advance from the
preceding selected state. Do not delete or edit old raw/canonical records to make a rebuild pass.
`provider_reorder` means one provider event would be selected at two sequences; require a full
unambiguous corrected ordering before retrying.

The reconciliation worker polls `PENDING` and claims abandoned `REBUILDING` work after five
minutes. Check its Compose health and logs if pending age rises. A bounded local pass is
`./gradlew :apps:queue-replay-cli:run --args='--reconciliation-run'`. Do not run
`--reset-import` against a shared or user-owned database; it truncates fixture/demo tables.

After repair, verify the game snapshot checksum/ETag, selected public event history, the
`RESYNC_REQUIRED` publication, corrected owner alerts, and delivery attempts. Previously sent
emails cannot be retracted. If a delivery was leased during correction, review whether SMTP may
already have accepted it before replaying any DLQ item.
