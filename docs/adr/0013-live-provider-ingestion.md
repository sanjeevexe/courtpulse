# ADR 0013: Ingest one live provider behind the replay-first boundary

- Status: Accepted (adapter verified against a local simulator; no paid provider access used)
- Date: 2026-09-28

## Context

Every earlier milestone ran on authored fixtures. The plan calls for one live basketball provider
behind an internal interface, with rate limits, visible staleness, raw evidence, and corrections,
while keeping every capability reproducible offline. BALLDONTLIE documents a v1 API
(`https://docs.balldontlie.io`, reviewed 2026-09-28): games and teams are available on its free
tier, but play-by-play (`GET /v1/plays?game_id=`) requires the paid GOAT tier (about USD 40 per
month, 600 requests per minute). A student portfolio cannot assume that subscription, and its
terms must be reviewed by the owner before any real data is stored or displayed.

## Decision

`LiveGameProvider` is the only boundary the rest of the system sees. The BALLDONTLIE adapter owns
HTTP, validation, and mapping; it sends the API key only in the `Authorization` header, redacts it
from every `toString`, accepts plain HTTP only for loopback or the named local simulator, bounds
response size and time, spaces requests under a configured per-minute quota, honors
`Retry-After`, and opens a circuit after five consecutive upstream failures. Every failure is a
fixed code (`http_429`, `timeout`, `circuit_open`, ...) so logs and metrics never carry provider
text.

Mapping is per play and context free, so refetching a play always yields the same evidence. The
provider `order` is the canonical sequence and `<game_id>:<order>` the provider identity; order 1
starts the game, `End Game` finishes it, `scoring_play` with `score_value` 1-3 becomes a made free
throw or field goal credited to the listed team and first participant, and all other plays become
`PLAY_RECORDED` with a sanitized, 280-character description for display only. The canonical raw
payload is the play re-serialized with sorted keys, so provider key order cannot fake a change.

Each poll compares content hashes with stored evidence. A new identity goes forward through the
existing transactional outbox. A changed body becomes the next revision and is submitted to the
existing reconciliation path, which rebuilds state and marks invalidated alerts `CORRECTED`. A body
that returns to an older revision, an identity that disappears, or a play that cannot be mapped
becomes an idempotent `provider_data_incidents` row for an operator; nothing is guessed. An
unmappable play is therefore a gap: later plays wait instead of skipping it. If the provider
reports a game final but never sends `End Game`, a derived final with its own identity and raw
evidence is recorded after a 60-second quiet period. Completed games are re-polled every 10
minutes for six hours because providers revise box scores after games end.

Freshness is the last successful poll per game (`provider_game_observations`), not the last
event, so halftime is not stale and an outage is never fresh. Read APIs derive `dataStatus` from
the later of checkpoint time and that poll; the snapshot ETag now covers data status and display
names so a cached `304` cannot keep presenting a stale game as live. Team and player names are
public provider metadata in their own tables and fall back to stable IDs.

A local provider simulator (`apps/provider-simulator`) serves one fictional overtime game in the
documented response shapes, on a scaled clock or on demand, with injectable 429s, outages, and a
scorer correction. It exists only for development and acceptance tests and is never deployed.
Periods now run to 10 (six overtimes) with five-minute overtime clocks; close-game rules may
target an overtime period.

## Consequences

The ingestion path is exercised end to end without a subscription, and switching to the real API
is configuration: `COURTPULSE_PROVIDER_BASE_URL`, `COURTPULSE_BALLDONTLIE_API_KEY`, and a
requests-per-minute value that matches the purchased tier. Assumptions that only real data can
confirm are explicit: `order` is contiguous from 1, `End Game` is published, and corrections
change a play's body rather than its order. A violation surfaces as a blocked gap or an incident,
not silent state. Real provider payloads are licensed data; they are stored only as raw evidence
in PostgreSQL and bounded incident rows, never committed to the repository or sent to telemetry.
The previous release can still read every regulation-only row after V10; overtime rows require
this release, so rolling back after an overtime game is ingested is not supported.
