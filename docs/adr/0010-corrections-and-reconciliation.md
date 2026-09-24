# ADR 0010: Rebuild corrected games from immutable event revisions

- Status: Accepted
- Date: 2026-09-23

## Context

The existing processor accepts only the next sequence and commits a checkpoint, processed
identity, scoring run, alerts, and outbox rows together. Passing an older revision to that
processor cannot repair a completed game. V1 also makes `(game, sequence, revision)` and
`(source, game, provider event, revision)` unique, so a same-revision disagreement must be
recorded outside those canonical tables rather than overwriting the accepted payload.

## Decision

Raw and canonical events are append-only. A byte-identical redelivery increments observation
metadata and does not request another rebuild. A new revision or previously missing sequence
requests reconciliation for that game. A changed payload under an existing provider identity
or a second provider identity claiming the same sequence and revision is an ambiguous conflict:
retain the rejected raw observation, mark the game blocked, and require a newer unambiguous
revision. A revision with invalid state-transition facts is retained; it is skipped only when a
lower valid revision exists. If no candidate is valid, the game is blocked with a safe error code.

For each sequence, choose the highest valid provider revision. Selection is independent of
arrival order. Valid means that the event passes schema, game-context, and score-transition
checks against the selected preceding state. If the highest candidate is malformed, record the
failure and examine lower revisions; if none is valid, block. A missing sequence blocks at the
first gap. Selection does not skip a sequence, invent a play, or advance from a final state.
If the same provider event identity would occupy two selected sequences after a reorder, block
as `provider_reorder` instead of double-counting the play.
The selected event and raw-payload IDs for every successful attempt are recorded by generation;
all old revisions and attempt outcomes remain queryable for audit.

The lifecycle is `PENDING` → `REBUILDING` → `COMPLETED` or `UNCHANGED`, or `BLOCKED` on a
gap/conflict/invalid history. Each attempt receives a monotonic per-game generation. A crash
after the durable `REBUILDING` claim is recoverable on restart; the abandoned attempt becomes
`RETRIED` when a later claim starts. `BLOCKED` is not automatically
polled in a hot loop: only newly accepted source data
may move it to `PENDING`. Workers serialize by the game checkpoint row, but unrelated games
can reconcile independently. The complete replay and replacement of checkpoint, processed
identities, scoring run, alert validity, and correction hint are one PostgreSQL transaction.
No intermediate score is public. A concurrent forward worker must not apply superseded data.

Rules are evaluated against the selected history in order. Existing alert keys retain their
identity and delivery history. Alerts no longer justified by that history become `CORRECTED`,
linked to the correction and generation. Pending email work for them is cancelled; delivered
or ambiguously accepted email attempts are immutable and cannot be retracted. A revalidated
existing alert is never sent again. Genuinely new keys create delivery intent only once via
the existing uniqueness boundary. Rule edits made before replay take effect for this rebuild;
historic rule configuration is not reconstructed. This is visible as a limitation in the
reconciliation audit, not presented as original-time truth.
An email already leased when correction commits may still be accepted by SMTP; its SENT attempt
is retained. A subsequent transient failure or expired lease for a corrected alert is cancelled
without another send. An expired in-flight lease records UNKNOWN_ACCEPTANCE rather than claiming
that the email was not sent.

HTTP snapshot validators include the state checksum, so changing history changes ETag even
when sequence count is unchanged. Public event history shows selected revisions, while raw
and rejected revisions stay in the private audit store. A correction-specific `RESYNC_REQUIRED`
hint bypasses the ordinary WebSocket version filter. It carries only game ID, state version,
and a fixed reason; private rule details and owner identity remain in owner-scoped HTTP only.
Operational visibility is aggregate and protected.

## Consequences

PostgreSQL remains the correctness boundary. Queue deduplication and WebSocket hints remain
best-effort accelerators. A completed game can be repaired without altering the original
provider record or erasing sent-email history. Gaps and ambiguity require new evidence rather
than repeated retries. Historical rule edits are not reconstructable until rule-version
snapshots are introduced.
