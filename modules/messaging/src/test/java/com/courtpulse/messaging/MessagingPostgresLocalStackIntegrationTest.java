package com.courtpulse.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.courtpulse.domain.alert.PlayerMilestoneRule;
import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.replay.StateChecksum;
import com.courtpulse.messaging.consumer.CanonicalEventEnvelopeValidator;
import com.courtpulse.messaging.consumer.ConsumerBatchResult;
import com.courtpulse.messaging.consumer.ConsumerFailureMode;
import com.courtpulse.messaging.consumer.GameEventQueueConsumer;
import com.courtpulse.messaging.consumer.SimulatedConsumerCrashException;
import com.courtpulse.messaging.delivery.DeliveryQueueConsumer;
import com.courtpulse.messaging.delivery.DeliveryQueuePublisher;
import com.courtpulse.messaging.delivery.EmailSendException;
import com.courtpulse.messaging.publisher.OutboxPublisher;
import com.courtpulse.messaging.publisher.PublicationRetryPolicy;
import com.courtpulse.messaging.publisher.PublisherBatchResult;
import com.courtpulse.messaging.publisher.PublisherFailureMode;
import com.courtpulse.messaging.publisher.SimulatedPublisherCrashException;
import com.courtpulse.messaging.queue.GameEventEnvelopeCodec;
import com.courtpulse.messaging.queue.GameEventEnvelope;
import com.courtpulse.messaging.queue.QueueDepth;
import com.courtpulse.messaging.queue.QueuePort;
import com.courtpulse.messaging.queue.QueuePublishException;
import com.courtpulse.messaging.queue.QueueSendRequest;
import com.courtpulse.messaging.queue.ReceivedQueueMessage;
import com.courtpulse.messaging.queue.SendOutcome;
import com.courtpulse.messaging.sqs.SqsQueueAdapter;
import com.courtpulse.persistence.DurableGameProcessor;
import com.courtpulse.persistence.FixtureIngestionService;
import com.courtpulse.persistence.JdbcFixtureRepository;
import com.courtpulse.persistence.JdbcAlertDeliveryRepository;
import com.courtpulse.persistence.JdbcDeliveryWorkRepository;
import com.courtpulse.persistence.JdbcGameProcessingRepository;
import com.courtpulse.persistence.JdbcInspectionRepository;
import com.courtpulse.persistence.JdbcOutboxPublicationRepository;
import com.courtpulse.persistence.JdbcOutboxRepository;
import com.courtpulse.persistence.LeasedOutboxRecord;
import com.courtpulse.persistence.OutboxStatusCounts;
import com.courtpulse.testkit.SyntheticFixtureResources;
import com.courtpulse.testkit.SyntheticLoadGames;
import com.courtpulse.providers.fixture.FixtureGame;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.providers.fixture.LoadedSourceEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Testcontainers
class MessagingPostgresLocalStackIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @Container
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.4.0")).withServices("sqs");

    private static final Instant START = Instant.parse("2026-01-01T01:00:00Z");
    private static DataSource dataSource;
    private static SqsClient sqs;

    private Services services;
    private String queueUrl;
    private String dlqUrl;

    @BeforeAll
    static void initializeInfrastructure() {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setURL(POSTGRES.getJdbcUrl());
        postgres.setUser(POSTGRES.getUsername());
        postgres.setPassword(POSTGRES.getPassword());
        dataSource = postgres;
        Flyway.configure().dataSource(dataSource).load().migrate();
        sqs = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    @BeforeEach
    void resetState() {
        services = services(new MutableClock(START));
        services.fixtures().resetAll();
        String suffix = UUID.randomUUID().toString();
        dlqUrl = createQueue("court-pulse-dlq-" + suffix + ".fifo", Map.of(
                QueueAttributeName.FIFO_QUEUE, "true",
                QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false"));
        String dlqArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN).build())
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        queueUrl = createQueue("court-pulse-" + suffix + ".fifo", Map.of(
                QueueAttributeName.FIFO_QUEUE, "true",
                QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false",
                QueueAttributeName.VISIBILITY_TIMEOUT, "1",
                QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "1",
                QueueAttributeName.REDRIVE_POLICY,
                "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"3\"}"));
    }

    @AfterEach
    void removeQueues() {
        sqs.deleteQueue(builder -> builder.queueUrl(queueUrl));
        sqs.deleteQueue(builder -> builder.queueUrl(dlqUrl));
    }

    @Test
    void sqsTraceparentRoundTripsAsMetadataWithoutChangingPrivateBody() {
        String parent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        SqsQueueAdapter queue = new SqsQueueAdapter(sqs, queueUrl);
        queue.send(new QueueSendRequest("opaque-delivery-id", "safe-group", "safe-dedup", parent));
        var received = queue.receive(1, Duration.ofSeconds(2));
        assertEquals(1, received.size());
        assertEquals("opaque-delivery-id", received.getFirst().body());
        assertEquals(parent, received.getFirst().traceparent());
    }

    @Test
    void leasesPreventDoubleClaimsPreserveGroupOrderAndAllowIndependentGroups() {
        importFixture();
        JdbcOutboxPublicationRepository repository = services.publications();

        List<LeasedOutboxRecord> first = inTransaction(
                () -> repository.claimGameEvents("publisher-a", 10, START, Duration.ofSeconds(30)));
        List<LeasedOutboxRecord> blocked = inTransaction(
                () -> repository.claimGameEvents("publisher-b", 10, START, Duration.ofSeconds(30)));

        assertEquals(1, first.size());
        assertEquals(1, first.getFirst().sequence());
        assertTrue(blocked.isEmpty());
        assertFalse(inTransaction(() -> repository.markSent(first.getFirst().outboxId(), "publisher-b", START)));
        assertTrue(inTransaction(() -> repository.markSent(first.getFirst().outboxId(), "publisher-a", START)));
        assertEquals(2, inTransaction(() -> repository.claimGameEvents(
                "publisher-b", 1, START, Duration.ofSeconds(30))).getFirst().sequence());

        services.fixtures().resetAll();
        LoadedFixture primary = importFixture();
        LoadedFixture secondary = secondGameFixture(primary);
        assertEquals(1, services.ingestion().importFixture(secondary).insertedCanonicalEvents());
        List<LeasedOutboxRecord> independent = inTransaction(
                () -> repository.claimGameEvents("publisher-c", 10, START, Duration.ofSeconds(30)));
        assertEquals(2, independent.size());
        assertEquals(
                List.of(primary.game().gameId(), secondary.game().gameId()),
                independent.stream().map(LeasedOutboxRecord::gameId).sorted().toList());
    }

    @Test
    void concurrentPublishersCannotClaimTheSameRowOrSkipAhead() throws Exception {
        importFixture();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<LeasedOutboxRecord>> first = executor.submit(() -> {
                start.await();
                return inTransaction(() -> services.publications()
                        .claimGameEvents("concurrent-a", 10, START, Duration.ofSeconds(30)));
            });
            Future<List<LeasedOutboxRecord>> second = executor.submit(() -> {
                start.await();
                return inTransaction(() -> services.publications()
                        .claimGameEvents("concurrent-b", 10, START, Duration.ofSeconds(30)));
            });
            start.countDown();
            List<LeasedOutboxRecord> combined = java.util.stream.Stream.concat(
                            first.get().stream(), second.get().stream())
                    .toList();
            assertEquals(1, combined.size());
            assertEquals(1, combined.getFirst().sequence());
        }
    }

    @Test
    void expiredLeaseIsReclaimedAndPreviousOwnerLosesAuthority() {
        importFixture();
        LeasedOutboxRecord original = inTransaction(() -> services.publications()
                .claimGameEvents("dead-publisher", 1, START, Duration.ofSeconds(10)).getFirst());

        assertTrue(inTransaction(() -> services.publications()
                .claimGameEvents("replacement", 1, START.plusSeconds(9), Duration.ofSeconds(10))).isEmpty());
        assertFalse(inTransaction(() -> services.publications()
                .markSent(original.outboxId(), "dead-publisher", START.plusSeconds(11))));
        assertFalse(inTransaction(() -> services.publications()
                .markFailed(original.outboxId(), "dead-publisher", START.plusSeconds(11), "expired")));
        LeasedOutboxRecord reclaimed = inTransaction(() -> services.publications()
                .claimGameEvents("replacement", 1, START.plusSeconds(11), Duration.ofSeconds(10)).getFirst());

        assertEquals(original.outboxId(), reclaimed.outboxId());
        assertEquals(2, reclaimed.attempt());
        assertFalse(inTransaction(() -> services.publications()
                .markSent(original.outboxId(), "dead-publisher", START.plusSeconds(12))));
        assertTrue(inTransaction(() -> services.publications()
                .markSent(original.outboxId(), "replacement", START.plusSeconds(12))));
    }

    @Test
    void publisherMarksSuccessRetriesTransientFailureAndTerminatesPermanentFailure() {
        importFixture();
        OutboxPublisher successful = publisher(services, new RecordingQueue(), "success");
        assertEquals(1, successful.publishBatch().sent());
        assertEquals(1, services.publications().statusCounts().sent());

        services.fixtures().resetAll();
        importFixture();
        OutboxPublisher transientPublisher = publisher(
                services, new FailingQueue(true), "transient");
        assertEquals(1, transientPublisher.publishBatch().retryScheduled());
        assertEquals(1, services.publications().statusCounts().retryScheduled());

        services.fixtures().resetAll();
        importFixture();
        OutboxPublisher permanentPublisher = publisher(
                services, new FailingQueue(false), "permanent");
        assertEquals(1, permanentPublisher.publishBatch().failed());
        assertEquals(1, services.publications().statusCounts().failed());
    }

    @Test
    void publicationErrorsAreSanitizedAndBounded() {
        importFixture();
        String unsafe = "password=super-secret " + "x".repeat(1_500);
        OutboxPublisher publisher = publisher(services, new MessageFailingQueue(true, unsafe), "publisher");

        assertEquals(1, publisher.publishBatch().retryScheduled());
        String stored = services.jdbc().sql("SELECT last_error FROM outbox WHERE status = 'RETRY_SCHEDULED'")
                .query(String.class).single();
        assertEquals(1_000, stored.length());
        assertFalse(stored.contains("super-secret"));
        assertTrue(stored.contains("password=[redacted]"));
    }

    @Test
    void batchedPublishSendsOneRowPerGameThroughSqsBatchesAndSettlesEachEntry() throws Exception {
        SyntheticLoadGames.generate("batch", 12, 8, 7).forEach(game -> services.ingestion().importFixture(game));
        SqsQueueAdapter queue = new SqsQueueAdapter(sqs, queueUrl);
        OutboxPublisher publisher = publisher(services, queue, "batch-publisher", 50);

        PublisherBatchResult first = publisher.publishBatch();

        assertEquals(12, first.claimed(), "one row per game: later rows wait for their predecessor");
        assertEquals(12, first.sent());
        assertEquals(12, services.publications().statusCounts().sent());
        awaitDepth(queue, 12);
        assertEquals(12, queue.depth().visible(), "two SQS batch calls (10 + 2) delivered every entry");
        assertEquals(12, publisher.publishBatch().sent(), "the next claim takes each game's second row");

        services.fixtures().resetAll();
        SyntheticLoadGames.generate("partial", 5, 8, 7).forEach(game -> services.ingestion().importFixture(game));
        PublisherBatchResult partial = publisher(services, new PartiallyFailingQueue(), "partial", 50)
                .publishBatch();

        assertEquals(new PublisherBatchResult(5, 3, 1, 1, 0), partial);
        OutboxStatusCounts counts = services.publications().statusCounts();
        assertEquals(3, counts.sent());
        assertEquals(1, counts.retryScheduled());
        assertEquals(1, counts.failed());
    }

    @Test
    void mismatchedEnvelopeIsRejectedWithoutAcknowledgement() {
        LoadedFixture fixture = importFixture();
        LeasedOutboxRecord record = inTransaction(() -> services.publications()
                .claimGameEvents("publisher", 1, START, Duration.ofMinutes(1)).getFirst());
        GameEventEnvelope mismatch = new GameEventEnvelope(
                record.outboxId().toString(),
                GameEventEnvelope.MESSAGE_TYPE,
                GameEventEnvelope.CURRENT_SCHEMA_VERSION,
                record.eventId(),
                record.gameId(),
                record.sequence() + 1,
                record.source(),
                record.providerEventId(),
                record.revision(),
                record.occurredAt(),
                record.outboxId(),
                record.deduplicationKey(),
                null);
        SingleMessageQueue queue = new SingleMessageQueue(services.codec().encode(mismatch));

        ConsumerBatchResult result = consumer(services, queue).pollOnce();

        assertEquals(1, result.failed());
        assertFalse(queue.deleted);
        assertEquals(0, services.inspection().checkpointVersion(fixture.game().gameId()));
    }

    @Test
    void fullFifoReplayIsOrderedIdempotentAndLeavesDeferredNotificationsUntouched() {
        LoadedFixture fixture = importFixture();
        // This test is about order and idempotency, not redelivery: with the shared 1 s visibility
        // timeout, a slow batch (a busy CI runner) would see its messages redelivered until the
        // redrive policy moved them to the dead-letter queue.
        sqs.setQueueAttributes(builder -> builder.queueUrl(queueUrl)
                .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "30")));
        SqsQueueAdapter queue = new SqsQueueAdapter(sqs, queueUrl);
        OutboxPublisher publisher = publisher(services, queue, "publisher-full");
        long sent = 0;
        for (int attempt = 0; attempt < 25 && sent < 20; attempt++) {
            sent += publisher.publishBatch().sent();
        }
        assertEquals(20, sent);
        assertEquals(20, queue.depth().total());

        GameEventQueueConsumer consumer = consumer(services, queue);
        ConsumerBatchResult total = ConsumerBatchResult.empty();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (total.accepted() < 20 && System.nanoTime() < deadline) {
            total = total.plus(consumer.pollOnce());
        }

        GameState state = services.processing().readCheckpoint(fixture.game().gameId());
        OutboxStatusCounts outbox = services.publications().statusCounts();
        assertEquals(20, total.accepted());
        assertEquals(20, services.inspection().checkpointVersion(state.gameId()));
        assertEquals(1, services.processing().listAlerts(state.gameId()).size());
        assertEquals(18, state.homeScore());
        assertEquals(14, state.awayScore());
        assertEquals(13, state.pointsFor("player_ace"));
        assertEquals(
                "06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca",
                StateChecksum.sha256(state));
        assertEquals(20, outbox.sent());
        assertEquals(21, outbox.deferred());
        assertEquals(0, queue.depth().total());

        LeasedOutboxRecord alertEvent = recordForSequence(11);
        GameEventEnvelope duplicate = envelope(alertEvent);
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(services.codec().encode(duplicate))
                .messageGroupId(alertEvent.gameId())
                .messageDeduplicationId("forced-redelivery-" + UUID.randomUUID())
                .build());
        ConsumerBatchResult redelivery = consumer.pollOnce();
        assertEquals(1, redelivery.suppressed());
        assertEquals(1, redelivery.deleted());
        assertEquals(20, services.inspection().checkpointVersion(state.gameId()));
        assertEquals(1, services.processing().listAlerts(state.gameId()).size());
    }

    @Test
    void publisherAndConsumerCrashWindowsRecoverSafelyAcrossNewInstances() throws Exception {
        LoadedFixture fixture = importFixture();
        MutableClock clock = services.clock();
        SqsQueueAdapter queue = new SqsQueueAdapter(sqs, queueUrl);
        OutboxPublisher crashed = publisher(services, queue, "publisher-before-crash");
        assertThrows(SimulatedPublisherCrashException.class,
                () -> crashed.publishBatch(PublisherFailureMode.AFTER_SEND_BEFORE_SENT_UPDATE));
        assertEquals(1, services.publications().statusCounts().publishing());

        clock.advance(Duration.ofMinutes(2));
        assertEquals(1, publisher(services, queue, "publisher-after-restart").publishBatch().sent());
        GameEventQueueConsumer firstConsumer = consumer(services, queue);
        assertThrows(SimulatedConsumerCrashException.class,
                () -> firstConsumer.pollOnce(ConsumerFailureMode.AFTER_COMMIT_BEFORE_DELETE));
        assertEquals(1, services.inspection().checkpointVersion(fixture.game().gameId()));

        Thread.sleep(1_200);
        ConsumerBatchResult redelivery = consumer(services, queue).pollOnce();
        assertEquals(1, redelivery.suppressed());
        assertEquals(1, redelivery.deleted());
        assertEquals(1, services.inspection().checkpointVersion(fixture.game().gameId()));
    }

    @Test
    void failureBeforeCommitRedeliversAndPoisonMessageReachesDlq() throws Exception {
        LoadedFixture fixture = importFixture();
        SqsQueueAdapter queue = new SqsQueueAdapter(sqs, queueUrl);
        assertEquals(1, publisher(services, queue, "publisher").publishBatch().sent());
        ConsumerBatchResult failure = consumer(services, queue)
                .pollOnce(ConsumerFailureMode.BEFORE_DATABASE_COMMIT);
        assertEquals(1, failure.failed());
        assertEquals(0, services.inspection().checkpointVersion(fixture.game().gameId()));
        Thread.sleep(1_200);
        assertEquals(1, consumer(services, queue).pollOnce().accepted());

        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody("{not-valid")
                .messageGroupId("poison-game").messageDeduplicationId(UUID.randomUUID().toString()).build());
        for (int delivery = 0; delivery < 3; delivery++) {
            assertEquals(1, consumer(services, queue).pollOnce().failed());
            Thread.sleep(1_200);
        }
        assertEquals(0, consumer(services, queue).pollOnce().received());
        SqsQueueAdapter dlq = new SqsQueueAdapter(sqs, dlqUrl);
        awaitDepth(dlq, 1);
        assertEquals(1, dlq.depth().visible());
    }

    @Test
    void unsupportedSchemaReachesDlq() throws Exception {
        String body = """
                {"messageId":"m","messageType":"CANONICAL_EVENT_READY","schemaVersion":2,
                 "eventId":"e","gameId":"unsupported-game","sequence":1,"source":"s",
                 "providerEventId":"p","revision":1,"occurredAt":"2026-01-01T00:00:00Z",
                 "outboxId":"8b8deea9-ab01-420f-a562-5d30c2073170",
                 "deduplicationKey":"d","correlationId":null}
                """;
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody(body)
                .messageGroupId("unsupported-game").messageDeduplicationId(UUID.randomUUID().toString()).build());
        SqsQueueAdapter queue = new SqsQueueAdapter(sqs, queueUrl);
        for (int delivery = 0; delivery < 3; delivery++) {
            assertEquals(1, consumer(services, queue).pollOnce().failed());
            Thread.sleep(1_200);
        }
        assertEquals(0, consumer(services, queue).pollOnce().received());
        SqsQueueAdapter dlq = new SqsQueueAdapter(sqs, dlqUrl);
        awaitDepth(dlq, 1);
        assertEquals(1, dlq.depth().visible());
    }

    @Test
    void databaseRejectsAmbiguousEqualSequenceAndRevision() {
        long constraintCount = services.jdbc().sql("""
                        SELECT COUNT(*) FROM pg_constraint
                        WHERE conname = 'uq_canonical_game_sequence_revision'
                        """).query(Long.class).single();
        assertEquals(1, constraintCount);
    }

    @Test
    void localstackDeliveryRetriesTransientEmailThenSuppressesDuplicateQueueMessage() {
        importFixture();
        UUID deliveryId = seedEmailDelivery("retry-owner", "retry-alert");
        JdbcDeliveryWorkRepository work = new JdbcDeliveryWorkRepository(services.jdbc());
        QueuePort queue = new SqsQueueAdapter(sqs, queueUrl);
        DeliveryQueuePublisher publisher = new DeliveryQueuePublisher(
                work, queue, services.transactions(), services.clock(), "retry-publisher");
        int[] sends = {0};
        DeliveryQueueConsumer consumer = new DeliveryQueueConsumer(
                work, queue, (address, subject, body) -> {
                    sends[0]++;
                    if (sends[0] == 1) throw new EmailSendException("local_smtp_unavailable", true);
                    return null;
                }, services.transactions(), services.clock(), "retry-consumer");
        assertEquals(1, publisher.publishBatch());
        assertEquals(1, consumer.pollOnce());
        assertEquals("RETRY_SCHEDULED", work.deliveryStatus(deliveryId));
        services.clock().advance(Duration.ofSeconds(30));
        assertEquals(1, publisher.publishBatch());
        assertEquals(1, consumer.pollOnce());
        assertEquals("DELIVERED", work.deliveryStatus(deliveryId));
        assertEquals(2, sends[0]);
        queue.send(new QueueSendRequest(deliveryId.toString(), deliveryId.toString(),
                "duplicate-" + UUID.randomUUID()));
        consumer.pollOnce();
        assertEquals(2, sends[0]);
        assertEquals(List.of("SENT", "TRANSIENT_FAILURE"), services.jdbc().sql("""
                SELECT outcome FROM delivery_attempts WHERE delivery_id = :id
                ORDER BY attempt_number DESC
                """).param("id", deliveryId).query(String.class).list());
    }

    @Test
    void permanentEmailFailureAndMalformedMessageCannotBlockOtherGroups() throws Exception {
        importFixture();
        UUID failedId = seedEmailDelivery("failed-owner", "failed-alert");
        JdbcDeliveryWorkRepository work = new JdbcDeliveryWorkRepository(services.jdbc());
        QueuePort queue = new SqsQueueAdapter(sqs, queueUrl);
        DeliveryQueuePublisher publisher = new DeliveryQueuePublisher(
                work, queue, services.transactions(), services.clock(), "failure-publisher");
        DeliveryQueueConsumer consumer = new DeliveryQueueConsumer(
                work, queue, (address, subject, body) -> {
                    throw new EmailSendException("smtp_permanent_rejection", false);
                }, services.transactions(), services.clock(), "failure-consumer");
        queue.send(new QueueSendRequest("not-a-uuid", "poison-group", "poison"));
        assertEquals(1, publisher.publishBatch());
        consumer.pollOnce();
        assertEquals("FAILED", work.deliveryStatus(failedId));
        assertEquals("smtp_permanent_rejection", services.jdbc().sql("""
                SELECT error_code FROM delivery_attempts WHERE delivery_id = :id
                """).param("id", failedId).query(String.class).single());
        for (int attempt = 0; attempt < 8 && new SqsQueueAdapter(sqs, dlqUrl).depth().total() == 0;
                attempt++) {
            Thread.sleep(1_100);
            consumer.pollOnce();
        }
        assertEquals(1, new SqsQueueAdapter(sqs, dlqUrl).depth().total());
    }

    private UUID seedEmailDelivery(String owner, String trigger) {
        UUID alertId = UUID.randomUUID();
        services.jdbc().sql("""
                INSERT INTO application_users(subject, created_at, last_seen_at)
                VALUES (:owner, now(), now())
                """).param("owner", owner).update();
        services.jdbc().sql("""
                INSERT INTO notification_preferences(owner_subject, email_enabled, updated_at)
                VALUES (:owner, TRUE, now())
                """).param("owner", owner).update();
        services.jdbc().sql("""
                INSERT INTO notification_destinations(
                    id, owner_subject, channel, address, enabled, created_at, updated_at)
                VALUES (:id, :owner, 'EMAIL', 'local@example.test', TRUE, now(), now())
                """).param("id", UUID.randomUUID()).param("owner", owner).update();
        String eventId = services.jdbc().sql("""
                SELECT event_id FROM canonical_events WHERE game_id = 'game_synthetic_001'
                ORDER BY sequence_number LIMIT 1
                """).query(String.class).single();
        services.jdbc().sql("""
                INSERT INTO alert_instances(
                    id, rule_id, game_id, trigger_key, triggering_event_id,
                    title, context, status, created_at, owner_subject, rule_type)
                VALUES (:id, :rule, 'game_synthetic_001', :trigger, :event,
                    'Local test alert', '{}'::jsonb, 'CREATED', now(), :owner, 'PLAYER_POINTS')
                """).param("id", alertId).param("rule", owner).param("trigger", trigger)
                .param("event", eventId).param("owner", owner).update();
        inTransaction(() -> {
            new JdbcAlertDeliveryRepository(services.jdbc()).createForAlert(
                    alertId, owner, services.clock().instant());
            return null;
        });
        return services.jdbc().sql("""
                SELECT id FROM alert_deliveries WHERE alert_id = :id AND channel = 'EMAIL'
                """).param("id", alertId).query(UUID.class).single();
    }

    private LoadedFixture importFixture() {
        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        assertEquals(20, services.ingestion().importFixture(fixture).insertedCanonicalEvents());
        return fixture;
    }

    private OutboxPublisher publisher(Services value, QueuePort queue, String owner) {
        return publisher(value, queue, owner, 10);
    }

    private OutboxPublisher publisher(Services value, QueuePort queue, String owner, int batchSize) {
        return new OutboxPublisher(
                value.publications(), queue, value.codec(), value.transactions(),
                new PublicationRetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(30), 3, () -> 0.0),
                value.clock(), owner, Duration.ofMinutes(1), batchSize);
    }

    private GameEventQueueConsumer consumer(Services value, QueuePort queue) {
        return new GameEventQueueConsumer(
                queue,
                value.codec(),
                new CanonicalEventEnvelopeValidator(value.processing(), value.publications()),
                value.processor(),
                10,
                Duration.ofSeconds(1));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return services.transactions().execute(status -> work.get());
    }

    private String createQueue(String name, Map<QueueAttributeName, String> attributes) {
        return sqs.createQueue(CreateQueueRequest.builder().queueName(name).attributes(attributes).build())
                .queueUrl();
    }

    private LeasedOutboxRecord recordForSequence(long sequence) {
        return services.jdbc().sql("""
                        SELECT outbox.id, outbox.deduplication_key, outbox.aggregate_id,
                               outbox.message_group_id, outbox.attempts,
                               event.game_id, event.sequence_number, event.source,
                               event.provider_event_id, event.revision, event.occurred_at
                        FROM outbox
                        JOIN canonical_events event ON event.event_id = outbox.aggregate_id
                        WHERE outbox.destination = 'GAME_EVENTS' AND event.sequence_number = :sequence
                        """).param("sequence", sequence)
                .query((resultSet, rowNumber) -> new LeasedOutboxRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("deduplication_key"),
                        resultSet.getString("aggregate_id"),
                        resultSet.getString("game_id"),
                        resultSet.getLong("sequence_number"),
                        resultSet.getString("source"),
                        resultSet.getString("provider_event_id"),
                        resultSet.getInt("revision"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        resultSet.getString("message_group_id"),
                        resultSet.getInt("attempts")))
                .single();
    }

    private static GameEventEnvelope envelope(LeasedOutboxRecord record) {
        return new GameEventEnvelope(
                record.outboxId().toString(),
                GameEventEnvelope.MESSAGE_TYPE,
                GameEventEnvelope.CURRENT_SCHEMA_VERSION,
                record.eventId(),
                record.gameId(),
                record.sequence(),
                record.source(),
                record.providerEventId(),
                record.revision(),
                record.occurredAt(),
                record.outboxId(),
                record.deduplicationKey(),
                null);
    }

    private static void awaitDepth(QueuePort queue, long expected) throws InterruptedException {
        for (int attempt = 0; attempt < 10 && queue.depth().visible() < expected; attempt++) {
            Thread.sleep(250);
        }
    }

    private static LoadedFixture secondGameFixture(LoadedFixture source) {
        CanonicalEvent original = source.events().getFirst();
        String gameId = "game_synthetic_002";
        CanonicalEvent event = new CanonicalEvent(
                "event_synthetic_002_001",
                original.schemaVersion(),
                gameId,
                original.source(),
                "provider_synthetic_002_001",
                1,
                1,
                original.type(),
                original.period(),
                original.clockMillisRemaining(),
                original.occurredAt(),
                original.teamId(),
                original.participantIds(),
                original.scoreAfter(),
                original.points());
        return new LoadedFixture(
                source.fixtureSchemaVersion(),
                "second-real-game",
                "Independent publication group",
                "test fixture",
                new FixtureGame(gameId, source.game().homeTeamId(), source.game().awayTeamId()),
                List.of(new LoadedSourceEvent(event, "{\"game\":\"second\"}", "b".repeat(64))));
    }

    private static Services services(MutableClock clock) {
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcFixtureRepository fixtures = new JdbcFixtureRepository(jdbc, mapper);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc, mapper);
        JdbcGameProcessingRepository processing = new JdbcGameProcessingRepository(jdbc, mapper);
        FixtureIngestionService ingestion = new FixtureIngestionService(fixtures, outbox, transactions, clock);
        DurableGameProcessor processor = new DurableGameProcessor(
                processing, outbox, transactions,
                List.of(new PlayerMilestoneRule("milestone-player-ace-10", "player_ace", 10)), clock);
        return new Services(
                clock, jdbc, transactions, fixtures, processing,
                new JdbcInspectionRepository(jdbc), ingestion, processor,
                new JdbcOutboxPublicationRepository(jdbc), new GameEventEnvelopeCodec(mapper));
    }

    private static class RecordingQueue implements QueuePort {
        @Override public String send(QueueSendRequest request) { return "message-1"; }
        @Override public List<ReceivedQueueMessage> receive(int max, Duration wait) { return List.of(); }
        @Override public void delete(String receiptHandle) {}
        @Override public QueueDepth depth() { return new QueueDepth(0, 0, 0); }
    }

    private static final class FailingQueue extends RecordingQueue {
        private final boolean retryable;
        private FailingQueue(boolean retryable) { this.retryable = retryable; }
        @Override public String send(QueueSendRequest request) {
            throw new QueuePublishException("simulated token=do-not-store", retryable);
        }
    }

    private static final class MessageFailingQueue extends RecordingQueue {
        private final boolean retryable;
        private final String message;
        private MessageFailingQueue(boolean retryable, String message) {
            this.retryable = retryable;
            this.message = message;
        }
        @Override public String send(QueueSendRequest request) {
            throw new QueuePublishException(message, retryable);
        }
    }

    /** Second entry fails transiently, third permanently, the rest succeed. */
    private static final class PartiallyFailingQueue extends RecordingQueue {
        @Override public List<SendOutcome> sendBatch(List<QueueSendRequest> requests) {
            return java.util.stream.IntStream.range(0, requests.size()).mapToObj(index -> switch (index) {
                case 1 -> SendOutcome.failed(new QueuePublishException("SQS batch entry failed: InternalError", true));
                case 2 -> SendOutcome.failed(new QueuePublishException("SQS batch entry failed: InvalidParameterValue", false));
                default -> SendOutcome.sent("message-" + index);
            }).toList();
        }
    }

    private static final class SingleMessageQueue extends RecordingQueue {
        private final String body;
        private boolean delivered;
        private boolean deleted;
        private SingleMessageQueue(String body) { this.body = body; }
        @Override public List<ReceivedQueueMessage> receive(int max, Duration wait) {
            if (delivered) {
                return List.of();
            }
            delivered = true;
            return List.of(new ReceivedQueueMessage("message-1", "receipt-1", body, 1));
        }
        @Override public void delete(String receiptHandle) { deleted = true; }
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private record Services(
            MutableClock clock,
            JdbcClient jdbc,
            TransactionTemplate transactions,
            JdbcFixtureRepository fixtures,
            JdbcGameProcessingRepository processing,
            JdbcInspectionRepository inspection,
            FixtureIngestionService ingestion,
            DurableGameProcessor processor,
            JdbcOutboxPublicationRepository publications,
            GameEventEnvelopeCodec codec) {}
}
