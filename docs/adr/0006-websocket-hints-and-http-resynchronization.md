# ADR 0006 Deliver WebSocket hints with HTTP resynchronization

- Status: Accepted
- Date: 2026-09-21

## Context

ETag-aware polling gives CourtPulse a durable and recoverable dashboard, but it cannot show a paced
replay with possession-level latency without frequent redundant HTTP reads. The database processor
already commits `GAME_STATE_UPDATED` and `ALERT_CREATED` outbox records in the same transaction as
each checkpoint and alert. Realtime delivery must build on that boundary without turning an
in-memory socket, an open transaction, or a browser-computed score into authoritative state.

## Decision

Use raw WebSockets at the versioned `/ws/v1/games` path for low-latency hints. Define the client and
server messages in `contracts/asyncapi/courtpulse-realtime-v1.yaml`. Every state-changing hint has a
schema version, message type, stable outbox-derived identity, game ID, state version, timestamp, and
correlation ID. Subscriptions include the game and the client’s last observed version.

### Durable publication

A dedicated publisher claims committed notification outbox rows under short PostgreSQL leases. The
claim transaction commits before any socket write, and a separate ownership-checked transaction
marks completion. Per-game eligibility prevents a newer version from passing an older unsent row;
different games remain independently claimable. Expired leases recover after a process crash.
Terminal failures remain `FAILED` with bounded sanitized error text and block newer hints for that
game until repaired.

A crash after broadcast but before completion can send a duplicate. The outbox UUID remains the
message ID across recovery, so clients can discard the duplicate. This is deliberate at-least-once
hint delivery, not exactly-once messaging.

### Authority, versions, and recovery

PostgreSQL-backed HTTP snapshots, canonical events, and alerts remain authoritative. WebSocket
messages contain identities and versions, not replacement score state. A client tracks the highest
accepted version, ignores duplicate identities and older versions, and invalidates the relevant HTTP
queries after a hint. A version gap, reconnect mismatch, malformed message, or explicit
`RESYNC_REQUIRED` invalidates snapshot, events, and alerts together. Existing ETags make an unchanged
snapshot refresh inexpensive.

Polling remains active whenever the socket is unavailable and is suppressed while a healthy socket
is connected. Reconnect uses bounded exponential backoff with jitter. Hidden documents stop
reconnect attempts and continue through the polling/focus behavior when visible again.

### Bounded clients and operations

The API caps sessions and each session has a fixed outbound queue drained by a virtual thread. A
full queue closes the slow client rather than blocking publication to other games. Inbound messages
are size bounded and accept only the documented subscription fields. Unknown games, invalid IDs,
unsupported versions, and malformed JSON receive sanitized protocol problems.

Metrics cover sessions, subscriptions, publications, stale/duplicate observations, resyncs,
slow-client disconnects, failures, and recovered leases. Structured logs identify game, version,
message, and correlation without logging headers, tokens, SQL, or provider payloads. Liveness and
readiness do not depend on connected browsers.

### Deployment scope

Nginx terminates the same-origin browser boundary and forwards WebSocket upgrade headers to the API.
This milestone deliberately supports one API instance. Redis or another shared fanout mechanism is
required before multiple API replicas can serve arbitrary WebSocket clients; adding it now would add
operational state without improving the single-instance milestone.

## Consequences

- Paced events become visible with low latency while durable HTTP remains the recovery path.
- Missing and duplicate socket frames cannot permanently corrupt the UI.
- The durable outbox owns crash recovery and makes terminal failures inspectable.
- A slow browser consumes bounded memory and cannot block other sessions.
- Realtime availability can degrade independently to polling without failing API liveness.
- Authentication and ownership, personalized rules, email, live providers, Redis fanout, Terraform,
  and AWS deployment remain deferred. The next milestone is authentication and ownership
  enforcement.
