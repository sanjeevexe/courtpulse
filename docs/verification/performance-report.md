# Performance report

- Date: 2026-09-28 (UTC 16:18 run)
- Environment: macOS, Apple M5 Pro; Docker Desktop VM with 15 CPUs and 8 GB. One Compose project:
  one API container, one processor container (4 consumer threads), PostgreSQL 17.11, LocalStack
  4.4.0 SQS FIFO, and k6 2.3.0, all on the same host. Images built from the working tree that
  became the M15 commit. Rate limiting disabled (a load generator is one client).
- Reproduce: `scripts/run-performance-tests.sh` (about 10 minutes). It keeps the host awake with
  `caffeinate`, warms the stack up before measuring, and aborts if the wall clock jumps during an
  import (a suspended Docker VM otherwise produces meaningless latencies). Raw k6 summaries, query
  plans, and logs land in `build/verification/performance/<project>/`.

Everything runs on one laptop, and LocalStack is not AWS. The numbers show the design meets its
targets with headroom on modest hardware; they are not a prediction of AWS latency. Every latency
comes from PostgreSQL commit timestamps or from k6, never from estimates.

## Results against the plan

| Plan target | Measured | Result |
| --- | --- | --- |
| Processing delay (canonical commit to checkpoint commit) p95 below 500 ms under a 200 events/s burst across 50 games | p95 82 ms, p99 99 ms, max 130 ms | Met |
| Alert decision delay p95 below 500 ms | Alerts commit in the checkpoint's transaction, so this equals processing delay: p95 82 ms | Met |
| Realtime delay (checkpoint commit to browser receipt) p95 below 2 s with 2,000 sustained WebSocket clients | Outbox-to-hint p95 244 ms plus fanout p95 19 ms (under 0.3 s combined) | Met |
| 50 concurrent games, 2,000 events | 2,000 events across 50 games at 203 events/s | Met |
| 100,000 stored rules without a full-table scan | 100,000 rules stored; hot-game lookup 0.9 ms using three index scans | Met |
| 10,000 alert evaluations from one scoring event | Capped at 1,000 per event by design; 130,000 evaluations across 10 hot games in 5 s | Deviation (see below) |
| API availability 99.5 percent | 33,298 requests, 0 failed, p99 2 ms | Met |

## 1. Sustained ingest at 200 events/s

50 games × 40 events, one event per game every 250 ms at a fixed rate.

| Events | Wall time (s) | Throughput (events/s) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
| --- | --- | --- | --- | --- | --- | --- |
| 2000 | 9.85 | 203.0 | 47 | 82 | 99 | 130 |

## 2. Saturation burst

The same 2,000 events committed back to back by one importer (about 2,000 events/s of ingress,
ten times the plan's burst). Throughput is the drain rate. Latency here is mostly each event
waiting behind earlier events of its own game, which FIFO ordering requires.

| Events | Wall time (s) | Throughput (events/s) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
| --- | --- | --- | --- | --- | --- | --- |
| 2000 | 3.46 | 578.3 | 1948 | 2817 | 3048 | 3243 |

## 3. Hot games (rule fanout)

10 games × 60 events, each game with 1,000 owned `PLAYER_POINTS` rules (the per-game cap) on its
star player, processed concurrently from a queue backlog. The rules table holds 100,000 rules
(90,000 more on 90 other games).

| Rule evaluations | Alerts created | Wall time (s) | Per-event step p50 (ms) | p95 (ms) | max (ms) |
| --- | --- | --- | --- | --- | --- |
| 130,000 | 8,800 | 4.99 | 12 | 273 | 482 |

The per-event step is the time between consecutive checkpoints of one game while all ten games
compete for the same processor. The rule lookup for one hot game:
`Bitmap Index Scan` on `idx_alert_rules_game_close` (0 rows), `idx_alert_rules_game_player`
(1,000 rows), and `idx_alert_rules_game_team` (0 rows), in 0.9 ms.

### Why one event evaluates at most 1,000 rules

The plan's stress row asks for 10,000 alert evaluations from one scoring event. CourtPulse caps
owned rules at 1,000 per game (and 50 per user) instead, for these reasons:

- Rule evaluation and alert creation happen inside the transaction that advances the game's
  checkpoint. That is what makes alerts exactly-once without a second coordination protocol.
- That transaction locks matching rules `FOR SHARE`, and the game's FIFO group cannot advance
  until it commits. Work per event grows with matching rules: at 1,000, the hot-game step p95 is
  273 ms, inside the 500 ms budget. Ten times that would push one game's events past the budget
  and hold its ordering hostage to a single slow commit.
- A portfolio deployment serves hundreds of users. The cap is enforced by the API with a clear
  error, so it cannot be exceeded silently.

The aggregate target is exceeded (130,000 evaluations in 5 s across ten games). Lifting the
per-event cap would mean asynchronous fanout: commit the checkpoint, then evaluate rule
partitions in separate idempotent jobs keyed by trigger. That trades immediate consistency for
throughput and is left as a documented extension.

## 4. Read API

k6 ramping arrival rate to 200 iterations/s (3 requests each: game list, conditional snapshot,
event page) for 90 s.

| Requests | Failed | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
| --- | --- | --- | --- | --- | --- |
| 33,298 | 0% | 1 | 1 | 2 | 62 |

## 5. Realtime fanout

2,000 WebSocket subscribers on one game while it receives 180 events at 2 per second. Fanout
latency is the server's `emittedAt` to receipt in k6 (same host clock). The outbox delay is
checkpoint commit to hint emission; the API polls the realtime outbox every 250 ms, which
dominates end-to-end delay.

| Subscribers | Hints received | Fanout p50 (ms) | Fanout p95 (ms) | Fanout max (ms) | Outbox p50 (ms) | Outbox p95 (ms) | Protocol problems |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2,000 | 279,892 | 9 | 19 | 35 | 132 | 244 | 0 |

All 2,000 upgrades succeeded. Clients join over about 20 s, as real clients arrive, so early
joiners receive more hints than late ones.

## Bottleneck found and fixed

The first run drained the saturation burst at 72 to 99 events/s. A diagnostic that compared
outbox timestamps showed the publisher needed 20.2 s to publish events that consumers then
processed at a published-to-processed p50 of 2 ms: the publisher claimed 10 rows per cycle and
sent one SQS message per call.

| Publisher | Saturation drain (events/s) |
| --- | --- |
| Before: claim 10, one `SendMessage` per row | 72 to 99 |
| Claim 50, `SendMessageBatch` of 10, sequential | 195 |
| Claim 50, batches of 10 sent concurrently (final) | 455 to 578 across runs |

Batching cannot reorder a game: a claim contains at most one row per game, because a row is
claimable only when every older row of its game is `SENT`. See
[ADR 0014](../adr/0014-batched-publication-and-transient-retry.md).

## Other findings from load testing

- **Contract bug.** Realtime messages serialized `emittedAt` as a numeric epoch (Jackson's
  default), but the AsyncAPI contract declares an RFC 3339 string. The fanout test surfaced it
  as nonsense latencies. The protocol encoder now writes ISO-8601 strings, and a test pins the
  format.
- **Measurement hazards.** One diagnostic run stalled for 169 s while the importer's monotonic
  clock measured 1.4 s: the laptop had suspended the Docker VM. The first burst after startup is
  also several times slower than later ones (JIT and connection warm-up). The script now keeps
  the host awake, warms up first, and fails the run on a clock jump.
