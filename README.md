# CourtPulse

CourtPulse is a replay-first basketball event platform. Its current production-shaped path is:

```text
synthetic fixture -> PostgreSQL transactional outbox -> lease-based publisher
                  -> SQS FIFO game-events queue -> queue consumer
                  -> durable processor -> PostgreSQL checkpoint, identities, alert, deferred outbox
```

The infrastructure-free and direct PostgreSQL replay commands remain available. This milestone
stops before HTTP APIs, UI, WebSockets, authentication, Redis, email, live providers, and cloud
deployment.

## Prerequisites and tests

- Java 21
- Docker Desktop with a healthy Linux engine and Docker Compose
- Internet access for the first dependency and image download

No global Gradle, PostgreSQL, LocalStack, or AWS CLI installation is required. Verify and test:

```bash
docker version
docker info
docker compose version
./gradlew clean test --rerun-tasks --console=plain
```

Docker is required for final validation; a skipped container test is not a successful validation.
Run the PostgreSQL/LocalStack messaging suite alone with:

```bash
./gradlew :modules:messaging:test --rerun-tasks --console=plain
```

Build and smoke-test the executable queue application:

```bash
./gradlew :apps:queue-replay-cli:bootJar
./gradlew :apps:queue-replay-cli:run --args='--help'
java -jar apps/queue-replay-cli/build/libs/queue-replay-cli-0.1.0-SNAPSHOT.jar --help
```

## Local PostgreSQL and SQS

Choose a local-only password and start both pinned services:

```bash
export COURTPULSE_DB_PASSWORD='courtpulse-local-dev'
docker compose config
docker compose up -d postgres localstack
docker compose ps
```

PostgreSQL and LocalStack must report healthy. The idempotent LocalStack ready hook creates:

- `game-events.fifo`: FIFO source queue, `MessageGroupId=gameId`, 8-second visibility timeout,
  2-second long poll, and explicit deduplication IDs.
- `game-events-dlq.fifo`: FIFO dead-letter queue.
- A redrive policy that moves a message after three failed receives.

Inspect the topology:

```bash
docker compose exec localstack awslocal sqs list-queues
docker compose exec localstack awslocal sqs get-queue-attributes \
  --queue-url http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/game-events.fifo \
  --attribute-names All
```

LocalStack is pinned to `localstack/localstack:4.4.0`. Only a configured endpoint override receives
the dummy local credentials; without an override, the AWS SDK default credential chain applies. No
real credentials or generated database data are committed.

## Queue-backed replay

The bounded command imports only when `--reset-import` is supplied. That explicit local-demo option
also purges both queues. Publish and drain the full fixture:

```bash
./gradlew :apps:queue-replay-cli:run --args='--reset-import --run'
```

Expected logical result:

```text
Imported: raw=20 canonical=20 outbox=20
Publisher: claimed=20 sent=20 retried=0 failed=0 lostLease=0
Consumer: received=20 accepted=20 suppressed=0 deleted=20 failed=0
Queue: visible=0 inFlight=0 delayed=0; DLQ visible=0
Outbox: pending=0 publishing=0 retry=0 sent=20 failed=0 deferred=21
Database: processed=20 checkpointVersion=20 alerts=1
Final score: HOME 18 - AWAY 14; player_ace=13
Final-state checksum: 06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca
Drain complete before deadline: true
```

Run phases or inspection in separate processes:

```bash
./gradlew :apps:queue-replay-cli:run --args='--reset-import --publish'
./gradlew :apps:queue-replay-cli:run --args='--drain'
./gradlew :apps:queue-replay-cli:run --args='--inspect'
./gradlew :apps:queue-replay-cli:run --args='--run'
```

The last command demonstrates restart safety: no eligible game-event outbox work remains and the
durable result is unchanged.

Configuration defaults are in `apps/queue-replay-cli/src/main/resources/application.yml`.
Overrides include `COURTPULSE_DB_URL`, `COURTPULSE_DB_USERNAME`, `COURTPULSE_DB_PASSWORD`,
`COURTPULSE_SQS_ENDPOINT`, `AWS_REGION`, queue names, batch sizes, lease/retry durations,
`COURTPULSE_CONSUMER_WORKERS` (1–8), and `COURTPULSE_DRAIN_TIMEOUT`.

## Failure and redelivery demonstrations

SQS accepted the send, then the publisher failed before recording `SENT`:

```bash
./gradlew :apps:queue-replay-cli:run --args='--reset-import --publish --simulate-publisher-after-send'
sleep 31
./gradlew :apps:queue-replay-cli:run --args='--publish --drain'
```

Consumer rollback before commit, then redelivery after visibility expires:

```bash
./gradlew :apps:queue-replay-cli:run --args='--reset-import --publish'
./gradlew :apps:queue-replay-cli:run --args='--drain --simulate-consumer-before-commit'
sleep 9
./gradlew :apps:queue-replay-cli:run --args='--drain'
```

Consumer commit followed by failure before delete; the next process suppresses the duplicate:

```bash
./gradlew :apps:queue-replay-cli:run --args='--reset-import --publish'
./gradlew :apps:queue-replay-cli:run --args='--drain --simulate-consumer-after-commit'
sleep 9
./gradlew :apps:queue-replay-cli:run --args='--drain'
```

Demonstrate poison-message redrive by sending malformed JSON. The bounded drain keeps long-polling
through the visibility intervals and exits after LocalStack redrives the third failed receive:

```bash
docker compose exec localstack awslocal sqs send-message \
  --queue-url http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/game-events.fifo \
  --message-body '{not-json' --message-group-id poison-game \
  --message-deduplication-id poison-1
./gradlew :apps:queue-replay-cli:run --args='--drain'
```

Inspect queue depth and receive the DLQ message without deleting it:

```bash
docker compose exec localstack awslocal sqs get-queue-attributes \
  --queue-url http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/game-events.fifo \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
docker compose exec localstack awslocal sqs receive-message \
  --queue-url http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/game-events-dlq.fifo
```

## Correctness boundaries

Flyway V2 adds explicit destination, message group, lease owner/expiration, and bounded last-error
fields without modifying V1. The state machine is `PENDING -> PUBLISHING -> SENT`,
`PUBLISHING -> RETRY_SCHEDULED -> PUBLISHING`, or `PUBLISHING -> FAILED`.

A short transaction claims eligible `GAME_EVENTS` rows with `FOR UPDATE SKIP LOCKED`, records the
owner and expiration, and commits before SQS is called. A second short transaction completes the row
only while the same publisher owns a still-valid lease. Expired rows are reclaimable.

Within one game, every older non-`SENT` row—including `FAILED`—blocks a newer row. Order is sequence,
revision, creation time, then outbox UUID. The V1 unique `(game_id, sequence_number, revision)`
constraint rejects ambiguous equal sequence/revision records. Different game groups remain
independently claimable.

The version-1 envelope carries type/schema, event/game/sequence, provider identity, occurrence time,
outbox ID, stable deduplication key, and optional correlation ID. Before processing, the consumer
compares this data with the committed canonical event and outbox row. It invokes the existing
durable processor and deletes only after its transaction commits. PostgreSQL processed identities
and alert trigger keys—not FIFO deduplication—remain authoritative.

`GAME_STATE_UPDATED` and `ALERT_CREATED` use `FUTURE_NOTIFICATIONS` and remain deferred. They are
never claimed by the game-events publisher.

The version-2 state checksum covers game/team IDs, status, period, clock, score, last sequence,
sorted player totals, every accepted provider identity plus canonical fingerprint, and bounded
recent-event history. Operational times and delivery counters are excluded.

## Modules

```text
modules/domain       pure events, reducer, rules, checksum (no infrastructure)
modules/providers    fixture mapping and raw source evidence
modules/persistence  Flyway, JDBC, checkpoints, idempotency, outbox leases
modules/messaging    queue port, publisher/consumer, AWS SDK v2 SQS adapter
modules/testkit      reusable synthetic fixture
apps/replay-cli      infrastructure-free replay
apps/durable-replay-cli  direct PostgreSQL replay
apps/queue-replay-cli    bounded PostgreSQL -> FIFO SQS -> PostgreSQL demo
```

## Troubleshooting and shutdown

- Docker unavailable: start Docker Desktop and ensure `docker info` succeeds for the same user.
- PostgreSQL authentication failure: use the password that initialized this Compose volume.
- LocalStack unhealthy: inspect `docker compose logs localstack`; SQS must show `running` and the
  ready hook must finish.
- Stale queue messages: use the explicit `--reset-import` local-demo action. SQS can reject repeated
  purge requests for 60 seconds.
- A row remains `PUBLISHING`: wait for lease expiry, then run `--publish`.
- An older `FAILED` row blocks its game by design; inspect and repair it rather than skipping ahead.

Stop without deleting data:

```bash
docker compose down
```

Intentionally delete only this Compose project's PostgreSQL and LocalStack volumes:

```bash
docker compose down -v
```

## Current limitations

- The synthetic fixture is the only provider; corrections, overtime, and live feeds are deferred.
- LocalStack is test infrastructure, not a production AWS deployment.
- Publication of notification-destination outbox rows is deliberately deferred.
- There is no REST API, UI, streaming, authentication, email, Terraform, or Kubernetes.

See [ADR 0001](docs/adr/0001-infrastructure-independent-domain.md),
[ADR 0002](docs/adr/0002-postgresql-transactional-outbox.md), and
[ADR 0003](docs/adr/0003-sqs-fifo-outbox-leasing.md).
