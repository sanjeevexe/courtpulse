# ADR 0009: Reliable local alert delivery

- Status: Accepted
- Date: 2026-09-22

## Context

Private rules create durable alert facts, but an email send cannot participate in the game
transaction. An SMTP server can accept a message immediately before the worker crashes, leaving
PostgreSQL unable to know whether the recipient received it.

## Decision

V7 creates an in-app delivery and, only for an opted-in owner with a local destination, an email
delivery plus outbox intent in the same transaction as the private alert. The outbox and SQS FIFO
payload contain only a delivery UUID. The worker resolves the address snapshot and private alert
content from PostgreSQL. Its publisher and consumer claim short leases in transactions, then call
SQS or Mailpit outside them. A continuously running, single-threaded `delivery-worker` Compose
service publishes, consumes, recovers expired leases, and writes a heartbeat for Docker health.
SIGTERM interrupts the loop. The manual `--delivery-run` mode is a bounded deterministic drain;
it does not wait for future retry times. The daemon does.

One SQS group per delivery prevents a poison message from blocking unrelated deliveries. Duplicate
messages find a terminal row and are acknowledged without another send. Five claims are allowed.
Retryable SQS and SMTP failures use capped exponential backoff with jitter; permanent failures and
exhaustion become terminal `FAILED`. Expired SMTP leases record an immutable
`UNKNOWN_ACCEPTANCE` attempt; an expired fifth lease becomes `FAILED`, never stranded `LEASED`.
Expired publisher leases are republished or failed at the same bound. Malformed queue payloads are
left to the dedicated SQS redrive policy and dead-letter queue. Operators inspect and explicitly
replay DLQ messages only after fixing the cause; blindly replaying an ambiguous SMTP send may
duplicate external mail.

Changing the email destination or opting out cancels pending and retry-scheduled deliveries and
their outbox work. A delivery already leased may complete against its immutable creation-time
address snapshot; an owner change never retargets that message. In-app history remains.
`delivery_attempts` rejects UPDATE but permits DELETE through ownership/alert cascade; this is
immutability while the owner exists, not permanent retention after user deletion.

Owner-scoped history and immutable attempts use bounded opaque-cursor pages. They expose safe error
codes and retry times but not addresses, SMTP identifiers, or message content. Public alert HTTP
and WebSocket feeds remain system-only. The operations endpoint and metrics have aggregate counts
and fixed names only, with no owner, rule, alert, delivery, or address tags. The API reports
backlog, oldest pending age, publications, attempts, successes, retries, terminal failures, and
lease recoveries. The worker observes DLQ depth in SQS and stores its aggregate value and observation
time for protected operations and metrics; the timestamp makes stale observations visible.

## Consequences

This is at-least-once external email, not exactly-once. SMTP acceptance followed by a crash before
database acknowledgement can send a duplicate after lease recovery. PostgreSQL prevents duplicate
logical delivery rows and duplicate sends after a recorded terminal state, but cannot retract an
accepted email. The adapter deliberately accepts only local Mailpit hosts and ports; real external
email, credential management, bounce handling, and production provider integration remain out of
scope. Queue and database state survive worker restart. Operators should retain the DLQ for
inspection and use the aggregate operations endpoint to detect backlog and exhausted work.
