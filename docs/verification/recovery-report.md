# Recovery drills

- Date: 2026-09-28 (UTC 16:16 run)
- Environment: macOS (Apple M5 Pro), Docker Desktop VM with 15 CPUs and 8 GB, one Compose project
  per run: PostgreSQL 17.11, LocalStack 4.4.0 (SQS FIFO), API, processor (2 consumer threads),
  and reconciliation worker, all built from the working tree that became the M15 commit
- Reproduce: `scripts/verify-recovery.sh` (or `DRILLS=sqs scripts/verify-recovery.sh` for one
  drill). Artifacts, including full logs and per-second progress for the SQS drill, are written
  under `build/verification/recovery/<project>/`.

Each drill interrupts one dependency while a 20-game, 800-event burst is in flight, with one
owned `PLAYER_POINTS` rule per game on a basket every game contains. Nobody intervenes: recovery
relies on restart policies, leases, retries, and idempotent processing. A drill passes only if
every accepted event has exactly one processed identity, every game ends with a `FINAL`
checkpoint at sequence 40, exactly one private alert exists per game, and no game-event outbox
row is `FAILED`.

## Results

| Drill | Interruption | Recovery to all events processed | Integrity |
| --- | --- | --- | --- |
| Processor SIGKILL mid-burst | killed, restarted after 3 s | 10 s | exactly once, one alert per game |
| PostgreSQL restart mid-burst | database restarted; API ready again in under 1 s | 2 s | exactly once, one alert per game |
| SQS unreachable for 20 s mid-burst | network partition; 65 of 800 published before it, 4 publications retried, no process restart | 27 s from the start of the outage (7 s after it ended) | exactly once, one alert per game |
| Processor SIGTERM mid-publication (rolling deploy) | graceful stop in under 1 s, then restarted | 24 s | exactly once, one alert per game |

The SQS and deploy drills run the processor with a one-row publisher so that publication is
still in progress when the interruption lands; the drill fails if no publication was retried,
because then it would prove nothing about the publisher. That setting also makes the remaining
publication after recovery slower than the default batched publisher would be.

SQS drill progress, sampled every second (from `sqs-timeline.txt`):

| Seconds after the partition began | Sent | Processed |
| --- | --- | --- |
| 0 to 21 | 69 | 69 |
| 22 | 143 | 148 |
| 24 | 433 | 444 |
| 26 | 730 | 742 |
| 27 | 800 | 800 |

(Each sample reads the sent count first, so processed can briefly exceed it.)

## What the drills found and fixed

The first runs failed, and each failure was a real defect rather than a flaky test. Every one of
them could turn a short, harmless infrastructure event into a permanently stalled game, because an
older `FAILED` outbox row blocks its game by design. The fixes are recorded in
[ADR 0014](../adr/0014-batched-publication-and-transient-retry.md).

1. **Network errors were permanent.** An unreachable SQS endpoint (`UnknownHostException`,
   `ConnectException`) was classified as a permanent failure. Now transient.
2. **Transient errors had a five-attempt budget** (about 7 to 15 s). Now they retry until they
   succeed with capped backoff; only permanent errors fail.
3. **Shutdown aborted in-flight sends.** The daemon interrupted workers at once on SIGTERM; the
   SDK turned the interrupt into `AbortedException`, which was classified as permanent. This
   failed a row in the SQS drill and would have done the same on any ECS deployment that landed
   mid-publish. Workers now get a 15 s grace period, and an aborted call is retryable.
4. **Telemetry could crash the processor.** A failed queue-depth read on the main loop exited the
   process, and consumers gave up after five failures (about 10 s), so an SQS blip became a
   restart loop and each restart left leases to expire. Telemetry failures are now logged and
   skipped, and workers ride out up to two minutes of continuous failure before restarting.
5. **Calls could hang for 30 s.** A partition drops packets silently, so calls waited out the
   socket timeout. Every SQS call now has a bounded attempt timeout.

Harness findings:

- Stopping LocalStack is not an SQS outage. LocalStack community keeps queues in memory, and its
  ready hook recreated empty queues on restart, discarding messages already marked `SENT`. Real
  SQS stores accepted messages redundantly, so the drill now detaches LocalStack from the network
  instead, which keeps its state.
- `COURTPULSE_PUBLISHER_BATCH_SIZE` was not passed through Compose, so the drill's one-row
  setting silently had no effect. `compose.yaml` now passes it to the processor.

## Not covered here

- Point-in-time database restore is a runbook (`docs/runbooks/database-restore.md`) that needs
  RDS; it has not been executed because the AWS environment has not been applied.
- API instance loss while browsers are connected is covered by the realtime design (clients
  resynchronize over HTTP), not by a drill.
- Terminal-failure repair tooling for rows that fail permanently remains future work.
