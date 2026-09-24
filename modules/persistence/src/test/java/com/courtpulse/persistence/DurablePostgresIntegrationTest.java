package com.courtpulse.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.courtpulse.domain.alert.PlayerMilestoneRule;
import com.courtpulse.domain.alert.RuleType;
import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.Score;
import com.courtpulse.domain.replay.StateChecksum;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.providers.fixture.LoadedSourceEvent;
import com.courtpulse.providers.fixture.FixtureGame;
import com.courtpulse.testkit.SyntheticFixtureResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class DurablePostgresIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.6-alpine");

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T01:00:00Z"), ZoneOffset.UTC);
    private static DataSource dataSource;
    private static MigrateResult migration;

    private Services services;

    @BeforeAll
    static void migrateEmptyDatabase() {
        PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();
        postgresDataSource.setURL(POSTGRES.getJdbcUrl());
        postgresDataSource.setUser(POSTGRES.getUsername());
        postgresDataSource.setPassword(POSTGRES.getPassword());
        dataSource = postgresDataSource;
        migration = Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void clearReferences() {
        dataSource = null;
    }

    @BeforeEach
    void resetDatabase() {
        services = createServices();
        services.fixtures().resetAll();
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE application_users CASCADE").update();
    }

    @Test
    void migrationsApplyAndFixtureImportIsIdempotent() {
        assertTrue(migration.migrationsExecuted >= 1);
        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();

        ImportResult first = services.ingestion().importFixture(fixture);
        ImportResult second = services.ingestion().importFixture(fixture);
        DatabaseCounts counts = services.inspection().counts();

        assertEquals(new ImportResult(20, 20, 20), first);
        assertEquals(new ImportResult(0, 0, 0), second);
        assertEquals(1, counts.games());
        assertEquals(20, counts.rawProviderPayloads());
        assertEquals(20, counts.canonicalEvents());
        assertEquals(1, counts.gameCheckpoints());
        assertEquals(20, counts.outboxRecords());
        JdbcClient jdbc = JdbcClient.create(dataSource);
        assertEquals(SystemDemoRuleId.forGame(fixture.game().gameId()),
                jdbc.sql("SELECT id FROM alert_rules WHERE game_id = :gameId")
                        .param("gameId", fixture.game().gameId())
                        .query(java.util.UUID.class).single());
    }

    @Test
    void processingPersistsExpectedStateAlertAndOutboxRows() {
        LoadedFixture fixture = importFixture();
        processAll(services, fixture, false);

        GameState state = services.processing().readCheckpoint(fixture.game().gameId());
        DatabaseCounts counts = services.inspection().counts();

        assertEquals(18, state.homeScore());
        assertEquals(14, state.awayScore());
        assertEquals(13, state.pointsFor("player_ace"));
        assertEquals(20, state.lastAppliedSequence());
        assertEquals(20, counts.processedEvents());
        assertEquals(1, counts.alertInstances());
        assertEquals(41, counts.outboxRecords());
        assertEquals(20, services.inspection().checkpointVersion(fixture.game().gameId()));
        assertEquals(20, services.inspection().countOutboxByType("GAME_STATE_UPDATED"));
        assertEquals(1, services.inspection().countOutboxByType("ALERT_CREATED"));
    }

    @Test
    void duplicateDeliveryDoesNotAdvanceCheckpointOrInsertAlertTwice() {
        LoadedFixture fixture = importFixture();
        RunCounts run = processAll(services, fixture, true);

        assertEquals(20, run.accepted());
        assertEquals(2, run.suppressed());
        assertEquals(20, services.inspection().checkpointVersion(fixture.game().gameId()));
        assertEquals(20, services.inspection().counts().processedEvents());
        assertEquals(1, services.inspection().counts().alertInstances());
        assertEquals(1, services.processing().listAlerts(fixture.game().gameId()).size());
        JdbcClient jdbc = JdbcClient.create(dataSource);
        String runBeforeRedelivery = jdbc.sql("""
                        SELECT team_id || '|' || points || '|' || start_sequence
                        FROM game_scoring_runs WHERE game_id = :gameId
                        """).param("gameId", fixture.game().gameId()).query(String.class).single();
        services.processing().listEventIds(fixture.game().gameId())
                .forEach(services.processor()::processEvent);
        String runAfterRedelivery = jdbc.sql("""
                        SELECT team_id || '|' || points || '|' || start_sequence
                        FROM game_scoring_runs WHERE game_id = :gameId
                        """).param("gameId", fixture.game().gameId()).query(String.class).single();
        assertEquals(runBeforeRedelivery, runAfterRedelivery);
    }

    @Test
    void restartAndReprocessingRetainChecksumAndLogicalAlertSet() {
        LoadedFixture fixture = importFixture();
        processAll(services, fixture, false);
        GameState beforeRestart = services.processing().readCheckpoint(fixture.game().gameId());
        String checksum = StateChecksum.sha256(beforeRestart);

        Services restarted = createServices();
        RunCounts reprocessed = processAll(restarted, fixture, false);
        GameState afterRestart = restarted.processing().readCheckpoint(fixture.game().gameId());

        assertEquals(0, reprocessed.accepted());
        assertEquals(20, reprocessed.suppressed());
        assertEquals(checksum, StateChecksum.sha256(afterRestart));
        assertEquals(1, restarted.processing().listAlerts(fixture.game().gameId()).size());
        assertEquals(20, restarted.inspection().checkpointVersion(fixture.game().gameId()));
    }

    @Test
    void failureBeforeCommitLeavesNoPartialProcessingState() {
        LoadedFixture fixture = importFixture();
        List<String> eventIds = services.processing().listEventIds(fixture.game().gameId());
        eventIds.subList(0, 10).forEach(eventId -> services.processor().processEvent(eventId));
        String milestoneEventId = eventIds.get(10);
        DatabaseCounts beforeFailure = services.inspection().counts();
        long checkpointVersion = services.inspection().checkpointVersion(fixture.game().gameId());
        GameState checkpointBeforeFailure = services.processing().readCheckpoint(fixture.game().gameId());

        assertThrows(
                SimulatedProcessingFailureException.class,
                () -> services.processor().processEvent(milestoneEventId, FailureMode.BEFORE_COMMIT));

        DatabaseCounts afterFailure = services.inspection().counts();
        assertEquals(checkpointVersion, services.inspection().checkpointVersion(fixture.game().gameId()));
        assertEquals(
                checkpointBeforeFailure,
                services.processing().readCheckpoint(fixture.game().gameId()));
        assertEquals(beforeFailure.processedEvents(), afterFailure.processedEvents());
        assertEquals(0, afterFailure.alertInstances());
        assertEquals(beforeFailure.outboxRecords(), afterFailure.outboxRecords());

        DurableProcessingResult retry = services.processor().processEvent(milestoneEventId);
        assertTrue(retry.accepted());
        assertEquals(checkpointVersion + 1, services.inspection().checkpointVersion(fixture.game().gameId()));
        assertEquals(1, services.inspection().counts().alertInstances());
        assertEquals(beforeFailure.outboxRecords() + 2, services.inspection().counts().outboxRecords());
    }

    @Test
    void failureAfterCommitIsSafeOnRedelivery() {
        LoadedFixture fixture = importFixture();
        String firstEventId = fixture.events().getFirst().eventId();

        assertThrows(
                SimulatedProcessingFailureException.class,
                () -> services.processor().processEvent(firstEventId, FailureMode.AFTER_COMMIT));

        assertEquals(1, services.inspection().checkpointVersion(fixture.game().gameId()));
        assertEquals(1, services.inspection().counts().processedEvents());
        DurableProcessingResult redelivery = createServices().processor().processEvent(firstEventId);
        assertFalse(redelivery.accepted());
        assertEquals(1, services.inspection().checkpointVersion(fixture.game().gameId()));
        assertEquals(1, services.inspection().countOutboxByType("GAME_STATE_UPDATED"));
    }

    @Test
    void databaseConstraintsBoundConcurrentDuplicateAttempts() throws Exception {
        LoadedFixture fixture = importFixture();
        String firstEventId = fixture.events().getFirst().eventId();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<DurableProcessingResult> first = executor.submit(() -> {
                start.await();
                return services.processor().processEvent(firstEventId);
            });
            Future<DurableProcessingResult> second = executor.submit(() -> {
                start.await();
                return services.processor().processEvent(firstEventId);
            });
            start.countDown();
            List<DurableProcessingResult> results = List.of(first.get(), second.get());

            assertEquals(1, results.stream().filter(DurableProcessingResult::accepted).count());
            assertEquals(1, results.stream().filter(result -> !result.accepted()).count());
        }

        assertEquals(1, services.inspection().checkpointVersion(fixture.game().gameId()));
        assertEquals(1, services.inspection().counts().processedEvents());
        assertEquals(1, services.inspection().countOutboxByType("GAME_STATE_UPDATED"));
    }

    @Test
    void realtimeClaimsPreservePerGameVersionAndAlertOrder() {
        LoadedFixture fixture = importFixture();
        services.processing().listEventIds(fixture.game().gameId()).stream()
                .limit(11)
                .forEach(eventId -> services.processor().processEvent(eventId));
        JdbcOutboxPublicationRepository publication =
                new JdbcOutboxPublicationRepository(JdbcClient.create(dataSource));
        List<RealtimeOutboxRecord> published = new ArrayList<>();

        for (int index = 0; index < 12; index++) {
            List<RealtimeOutboxRecord> claimed = publication.claimRealtime(
                    "realtime-a", 25, CLOCK.instant(), Duration.ofSeconds(15));
            assertEquals(1, claimed.size());
            RealtimeOutboxRecord record = claimed.getFirst();
            published.add(record);
            assertTrue(publication.markRealtimeSent(record.outboxId(), "realtime-a", CLOCK.instant()));
        }

        assertEquals(
                java.util.stream.LongStream.rangeClosed(1, 11).boxed().toList(),
                published.stream().limit(11).map(RealtimeOutboxRecord::stateVersion).toList());
        assertTrue(published.stream().limit(11)
                .allMatch(record -> "GAME_STATE_UPDATED".equals(record.eventType())));
        assertEquals("ALERT_CREATED", published.getLast().eventType());
        assertEquals(11, published.getLast().stateVersion());
    }

    @Test
    void expiredRealtimeLeaseRecoversSameHintAfterCrashBeforeCompletion() {
        LoadedFixture fixture = importFixture();
        services.processor().processEvent(fixture.events().getFirst().eventId());
        JdbcOutboxPublicationRepository publication =
                new JdbcOutboxPublicationRepository(JdbcClient.create(dataSource));
        RealtimeOutboxRecord first = publication.claimRealtime(
                "crashed", 10, CLOCK.instant(), Duration.ofSeconds(5)).getFirst();

        assertTrue(publication.claimRealtime(
                "replacement", 10, CLOCK.instant().plusSeconds(4), Duration.ofSeconds(5)).isEmpty());
        RealtimeOutboxRecord recovered = publication.claimRealtime(
                "replacement", 10, CLOCK.instant().plusSeconds(6), Duration.ofSeconds(5)).getFirst();

        assertEquals(first.outboxId(), recovered.outboxId());
        assertEquals(2, recovered.attempt());
        assertFalse(publication.markRealtimeSent(
                first.outboxId(), "crashed", CLOCK.instant().plusSeconds(6)));
        assertTrue(publication.markRealtimeSent(
                recovered.outboxId(), "replacement", CLOCK.instant().plusSeconds(7)));
    }

    @Test
    void terminalRealtimeFailureIsInspectableAndBlocksNewerSameGameHints() {
        LoadedFixture fixture = importFixture();
        services.processing().listEventIds(fixture.game().gameId()).stream()
                .limit(2)
                .forEach(eventId -> services.processor().processEvent(eventId));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        JdbcOutboxPublicationRepository publication = new JdbcOutboxPublicationRepository(jdbc);
        RealtimeOutboxRecord claimed = publication.claimRealtime(
                "realtime-a", 10, CLOCK.instant(), Duration.ofSeconds(5)).getFirst();
        assertTrue(publication.markRealtimeFailed(
                claimed.outboxId(), "realtime-a", CLOCK.instant().plusSeconds(1),
                "password=unsafe\nprovider failed"));

        assertEquals("FAILED", jdbc.sql("SELECT status FROM outbox WHERE id = :id")
                .param("id", claimed.outboxId()).query(String.class).single());
        assertEquals("password=[redacted] provider failed", jdbc.sql(
                        "SELECT last_error FROM outbox WHERE id = :id")
                .param("id", claimed.outboxId()).query(String.class).single());
        assertTrue(publication.claimRealtime(
                "realtime-b", 10, CLOCK.instant().plusSeconds(20), Duration.ofSeconds(5)).isEmpty());
    }

    @Test
    void terminalRealtimeFailureForOneGameDoesNotBlockAnotherGame() {
        LoadedFixture fixture = importFixture();
        services.processing().listEventIds(fixture.game().gameId()).stream()
                .limit(2)
                .forEach(eventId -> services.processor().processEvent(eventId));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        JdbcOutboxPublicationRepository publication = new JdbcOutboxPublicationRepository(jdbc);
        RealtimeOutboxRecord blocked = publication.claimRealtime(
                "realtime-a", 10, CLOCK.instant(), Duration.ofSeconds(5)).getFirst();
        assertTrue(publication.markRealtimeFailed(
                blocked.outboxId(), "realtime-a", CLOCK.instant().plusSeconds(1), "terminal"));

        UUID otherId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox(
                            id, deduplication_key, aggregate_type, aggregate_id, event_type,
                            payload, status, attempts, next_attempt_at, created_at,
                            destination, message_group_id)
                        VALUES (
                            :id, :deduplicationKey, 'Game', 'event-other', 'GAME_STATE_UPDATED',
                            CAST(:payload AS jsonb), 'PENDING', 0, :now, :now,
                            'FUTURE_NOTIFICATIONS', 'game-other')
                        """)
                .params(java.util.Map.of(
                        "id", otherId,
                        "deduplicationKey", "realtime-other-1",
                        "payload", "{\"stateVersion\":1,\"eventId\":\"event-other\"}",
                        "now", java.time.OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC)))
                .update();

        List<RealtimeOutboxRecord> next = publication.claimRealtime(
                "realtime-b", 10, CLOCK.instant().plusSeconds(20), Duration.ofSeconds(5));
        assertEquals(1, next.size());
        assertEquals(otherId, next.getFirst().outboxId());
        assertEquals("game-other", next.getFirst().gameId());
    }

    @Test
    void historicalRevisionRebuildsSelectedHistoryAndEmitsResyncOnlyOnce() {
        LoadedFixture fixture = importFixture();
        processAll(services, fixture, false);
        String gameId = fixture.game().gameId();
        String before = StateChecksum.sha256(services.processing().readCheckpoint(gameId));
        CanonicalEvent old = fixture.events().get(4);
        CanonicalEvent revised = new CanonicalEvent("event-005-rev-2", old.schemaVersion(),
                old.gameId(), old.source(), old.providerEventId(), old.sequence(), 2,
                old.type(), old.period(), old.clockMillisRemaining() - 1, old.occurredAt(),
                old.teamId(), old.participantIds(), old.scoreAfter(), old.points());
        GameReconciliationService reconciliation = reconciliation();
        LoadedFixture correction = correctionFixture(fixture, List.of(source(revised)));

        assertEquals(new GameReconciliationService.Submission(1, 0, 0),
                reconciliation.submit(correction));
        assertEquals("COMPLETED", reconciliation.reconcile(gameId).status());
        assertEquals(20, services.processing().readCheckpoint(gameId).lastAppliedSequence());
        assertNotEquals(before, StateChecksum.sha256(services.processing().readCheckpoint(gameId)));
        assertEquals(2, JdbcClient.create(dataSource).sql("""
                        SELECT revision FROM reconciliation_selected_events
                        WHERE game_id = :gameId AND generation = 1 AND sequence_number = 5
                        """).param("gameId", gameId).query(Integer.class).single());
        assertEquals(1, services.inspection().countOutboxByType("RESYNC_REQUIRED"));
        assertEquals(new GameReconciliationService.Submission(0, 1, 0),
                reconciliation.submit(correction));
        assertEquals("SKIPPED", reconciliation.reconcile(gameId).status());
        assertEquals(1, services.inspection().countOutboxByType("RESYNC_REQUIRED"));
        assertFalse(services.processor().processEvent(old.eventId()).accepted());
    }

    @Test
    void gapBlocksWithoutChangingCheckpointAndNewEvidenceUnblocksIt() {
        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        String gameId = fixture.game().gameId();
        GameReconciliationService reconciliation = reconciliation();
        reconciliation.submit(correctionFixture(fixture,
                List.of(fixture.sourceEvents().get(0), fixture.sourceEvents().get(2))));
        assertEquals("BLOCKED", reconciliation.reconcile(gameId).status());
        assertEquals(0, services.processing().readCheckpoint(gameId).lastAppliedSequence());
        assertEquals("SKIPPED", reconciliation.reconcile(gameId).status());

        reconciliation.submit(correctionFixture(fixture, List.of(fixture.sourceEvents().get(1))));
        assertEquals("COMPLETED", reconciliation.reconcile(gameId).status());
        assertEquals(3, services.processing().readCheckpoint(gameId).lastAppliedSequence());
        assertEquals(1, JdbcClient.create(dataSource).sql("""
                        SELECT count(*) FROM reconciliation_attempts
                        WHERE game_id = :gameId AND status = 'BLOCKED'
                        """).param("gameId", gameId).query(Long.class).single());
    }

    @Test
    void conflictingSameRevisionRetainsOriginalAndRejectedRawObservation() {
        LoadedFixture fixture = importFixture();
        processAll(services, fixture, false);
        var original = fixture.sourceEvents().get(4);
        LoadedSourceEvent disagreement = new LoadedSourceEvent(original.canonicalEvent(),
                "{\"conflicting\":true}", sha256("{\"conflicting\":true}"));
        GameReconciliationService reconciliation = reconciliation();
        assertEquals(1, reconciliation.submit(correctionFixture(fixture,
                List.of(disagreement))).conflicts());
        assertEquals("SKIPPED", reconciliation.reconcile(fixture.game().gameId()).status());
        assertEquals(1, JdbcClient.create(dataSource).sql("""
                        SELECT count(*) FROM reconciliation_conflict_observations
                        WHERE game_id = :gameId AND reason = 'RAW_IDENTITY_CONFLICT'
                        """).param("gameId", fixture.game().gameId()).query(Long.class).single());
        assertEquals(20, services.processing().readCheckpoint(fixture.game().gameId()).lastAppliedSequence());
        assertEquals(new GameReconciliationService.Submission(0, 1, 0),
                reconciliation.submit(correctionFixture(fixture, List.of(disagreement))));
        CanonicalEvent old = original.canonicalEvent();
        CanonicalEvent newer = new CanonicalEvent("event-005-rev-2", old.schemaVersion(),
                old.gameId(), old.source(), old.providerEventId(), old.sequence(), 2,
                old.type(), old.period(), old.clockMillisRemaining() - 1, old.occurredAt(),
                old.teamId(), old.participantIds(), old.scoreAfter(), old.points());
        reconciliation.submit(correctionFixture(fixture, List.of(source(newer))));
        assertEquals("COMPLETED", reconciliation.reconcile(fixture.game().gameId()).status());
    }

    @Test
    void correctionInvalidatesPrivateAlertPreservesSentAttemptAndCreatesNewIntentOnce() {
        LoadedFixture fixture = importFixture();
        String gameId = fixture.game().gameId();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcAlertRuleRepository rules = new JdbcAlertRuleRepository(jdbc, mapper);
        JdbcAlertDeliveryRepository deliveries = new JdbcAlertDeliveryRepository(jdbc);
        for (String owner : List.of("owner-a", "owner-b")) {
            jdbc.sql("""
                            INSERT INTO application_users(subject, created_at, last_seen_at)
                            VALUES (:owner, :now, :now)
                            """).param("owner", owner)
                    .param("now", CLOCK.instant().atOffset(ZoneOffset.UTC)).update();
            deliveries.saveSettings(owner, true, owner + "@example.test", CLOCK.instant());
        }
        rules.create("owner-a", "ace-threshold", new NewAlertRule(gameId,
                RuleType.PLAYER_POINTS, true, "player_ace", null, 10,
                null, null, null), CLOCK.instant());
        rules.create("owner-b", "other-threshold", new NewAlertRule(gameId,
                RuleType.PLAYER_POINTS, true, "player_home_2", null, 6,
                null, null, null), CLOCK.instant());
        JdbcGameProcessingRepository processing = new JdbcGameProcessingRepository(jdbc, mapper);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc, mapper);
        DurableGameProcessor processor = new DurableGameProcessor(processing, outbox, transactions,
                rules, CLOCK, RuleEngineMetrics.NONE);
        fixture.events().forEach(event -> processor.processEvent(event.eventId()));
        assertEquals(1, rules.listOwnedAlerts("owner-a", null, null, 10).size());
        assertEquals(0, rules.listOwnedAlerts("owner-b", null, null, 10).size());
        UUID sentAlert = rules.listOwnedAlerts("owner-a", null, null, 10).getFirst().id();
        UUID sentDelivery = jdbc.sql("""
                        UPDATE alert_deliveries SET status = 'DELIVERED', attempts = 1,
                            delivered_at = :now, next_attempt_at = NULL
                        WHERE alert_id = :alertId AND channel = 'EMAIL'
                        RETURNING id
                        """).param("now", CLOCK.instant().atOffset(ZoneOffset.UTC))
                .param("alertId", sentAlert).query(UUID.class).single();
        jdbc.sql("""
                        INSERT INTO delivery_attempts(id, delivery_id, attempt_number, outcome,
                            started_at, completed_at)
                        VALUES (:id, :deliveryId, 1, 'SENT', :now, :now)
                        """).param("id", UUID.randomUUID()).param("deliveryId", sentDelivery)
                .param("now", CLOCK.instant().atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                        UPDATE delivery_outbox SET status = 'SENT', published_at = :now
                        WHERE delivery_id = :deliveryId
                        """).param("now", CLOCK.instant().atOffset(ZoneOffset.UTC))
                .param("deliveryId", sentDelivery).update();

        CanonicalEvent old = fixture.events().get(10);
        CanonicalEvent revised = new CanonicalEvent("event-011-rev-2", old.schemaVersion(),
                old.gameId(), old.source(), old.providerEventId(), old.sequence(), 2,
                old.type(), old.period(), old.clockMillisRemaining(), old.occurredAt(),
                old.teamId(), List.of("player_home_2"), old.scoreAfter(), old.points());
        GameReconciliationService reconciliation = new GameReconciliationService(jdbc,
                new JdbcFixtureRepository(jdbc, mapper), processing, outbox, rules,
                transactions, CLOCK, mapper);
        LoadedFixture correction = correctionFixture(fixture, List.of(source(revised)));
        reconciliation.submit(correction);
        assertEquals("COMPLETED", reconciliation.reconcile(gameId).status());
        assertEquals("CORRECTED", rules.listOwnedAlerts("owner-a", null, null, 10).getFirst().status());
        assertEquals(1, rules.listOwnedAlerts("owner-b", null, null, 10).size());
        assertEquals("CREATED", rules.listOwnedAlerts("owner-b", null, null, 10).getFirst().status());
        assertEquals(1, deliveries.attempts("owner-a", sentDelivery, null, 10).size());
        assertEquals("DELIVERED", deliveries.history("owner-a", null, null, 10).stream()
                .filter(row -> row.id().equals(sentDelivery)).findFirst().orElseThrow().status());
        assertEquals(1, deliveries.history("owner-b", null, null, 10).stream()
                .filter(row -> "EMAIL".equals(row.channel())).count());
        assertTrue(rules.listOwnedAlerts("owner-a", null, null, 10).stream()
                .noneMatch(row -> row.id().equals(rules.listOwnedAlerts("owner-b", null, null, 10)
                        .getFirst().id())));

        assertEquals(1, reconciliation.submit(correction).duplicates());
        assertEquals("SKIPPED", reconciliation.reconcile(gameId).status());
        assertEquals(1, deliveries.history("owner-b", null, null, 10).stream()
                .filter(row -> "EMAIL".equals(row.channel())).count());
    }

    @Test
    void correctedAlertsCannotRetryAnInFlightOrExpiredEmailLease() {
        LoadedFixture fixture = importFixture();
        String gameId = fixture.game().gameId();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcAlertRuleRepository rules = new JdbcAlertRuleRepository(jdbc, mapper);
        JdbcAlertDeliveryRepository deliveries = new JdbcAlertDeliveryRepository(jdbc);
        for (String owner : List.of("lease-owner-a", "lease-owner-b")) {
            jdbc.sql("INSERT INTO application_users(subject, created_at, last_seen_at) VALUES (:owner, :now, :now)")
                    .param("owner", owner).param("now", CLOCK.instant().atOffset(ZoneOffset.UTC)).update();
            deliveries.saveSettings(owner, true, owner + "@example.test", CLOCK.instant());
            rules.create(owner, owner + "-rule", new NewAlertRule(gameId,
                    RuleType.PLAYER_POINTS, true, "player_ace", null, 10,
                    null, null, null), CLOCK.instant());
        }
        JdbcGameProcessingRepository processing = new JdbcGameProcessingRepository(jdbc, mapper);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc, mapper);
        DurableGameProcessor processor = new DurableGameProcessor(processing, outbox, transactions,
                rules, CLOCK, RuleEngineMetrics.NONE);
        fixture.events().forEach(event -> processor.processEvent(event.eventId()));
        JdbcDeliveryWorkRepository work = new JdbcDeliveryWorkRepository(jdbc);
        UUID first = jdbc.sql("SELECT id FROM alert_deliveries WHERE owner_subject = 'lease-owner-a' AND channel = 'EMAIL'")
                .query(UUID.class).single();
        UUID second = jdbc.sql("SELECT id FROM alert_deliveries WHERE owner_subject = 'lease-owner-b' AND channel = 'EMAIL'")
                .query(UUID.class).single();
        ClaimedEmailDelivery inFlight = transactions.execute(status ->
                work.claimDelivery(first, "worker-a", CLOCK.instant(), Duration.ofSeconds(30)));
        ClaimedEmailDelivery abandoned = transactions.execute(status ->
                work.claimDelivery(second, "worker-b", CLOCK.instant(), Duration.ofSeconds(30)));
        assertNotNull(inFlight);
        assertNotNull(abandoned);

        CanonicalEvent old = fixture.events().get(10);
        CanonicalEvent revised = new CanonicalEvent("event-011-rev-2-lease", old.schemaVersion(),
                old.gameId(), old.source(), old.providerEventId(), old.sequence(), 2,
                old.type(), old.period(), old.clockMillisRemaining(), old.occurredAt(),
                old.teamId(), List.of("player_home_2"), old.scoreAfter(), old.points());
        GameReconciliationService reconciliation = new GameReconciliationService(jdbc,
                new JdbcFixtureRepository(jdbc, mapper), processing, outbox, rules,
                transactions, CLOCK, mapper);
        reconciliation.submit(correctionFixture(fixture, List.of(source(revised))));
        assertEquals("COMPLETED", reconciliation.reconcile(gameId).status());

        assertTrue(Boolean.TRUE.equals(transactions.execute(status -> work.finishDelivery(inFlight, "worker-a",
                "TRANSIENT_FAILURE", "smtp_temporary", null, CLOCK.instant(),
                CLOCK.instant().plusSeconds(10)))));
        int recovered = transactions.execute(status ->
                work.recoverExpired(CLOCK.instant().plusSeconds(31)));
        assertEquals(1, recovered);
        for (UUID deliveryId : List.of(first, second)) {
            assertEquals("CANCELLED", work.deliveryStatus(deliveryId));
            assertEquals("FAILED", jdbc.sql("SELECT status FROM delivery_outbox WHERE delivery_id = :id")
                    .param("id", deliveryId).query(String.class).single());
            assertEquals(null, work.claimDelivery(deliveryId, "worker-c", CLOCK.instant().plusSeconds(31),
                    Duration.ofSeconds(30)));
        }
        assertEquals("TRANSIENT_FAILURE", jdbc.sql("SELECT outcome FROM delivery_attempts WHERE delivery_id = :id")
                .param("id", first).query(String.class).single());
        assertEquals("UNKNOWN_ACCEPTANCE", jdbc.sql("SELECT outcome FROM delivery_attempts WHERE delivery_id = :id")
                .param("id", second).query(String.class).single());
    }

    @Test
    void earlyScoreCorrectionArrivingOutOfOrderRebuildsFinalAndFinalRevisionWins() {
        LoadedFixture fixture = importFixture();
        processAll(services, fixture, false);
        String gameId = fixture.game().gameId();
        List<LoadedSourceEvent> revised = new ArrayList<>();
        for (int index = 2; index < fixture.events().size(); index++) {
            CanonicalEvent old = fixture.events().get(index);
            CanonicalEvent replacement = new CanonicalEvent(old.eventId() + "-rev-2",
                    old.schemaVersion(), old.gameId(), old.source(), old.providerEventId(),
                    old.sequence(), 2, old.type(), old.period(), old.clockMillisRemaining(),
                    old.occurredAt(), old.teamId(), old.participantIds(),
                    new Score(old.scoreAfter().home() + 1, old.scoreAfter().away()),
                    index == 2 ? 3 : old.points());
            revised.add(source(replacement));
        }
        java.util.Collections.reverse(revised);
        GameReconciliationService reconciliation = reconciliation();
        assertEquals(18, reconciliation.submit(correctionFixture(fixture, revised)).accepted());
        assertEquals("COMPLETED", reconciliation.reconcile(gameId).status());
        GameState corrected = services.processing().readCheckpoint(gameId);
        assertEquals(19, corrected.homeScore());
        assertEquals(14, corrected.awayScore());
        assertEquals(20, corrected.lastAppliedSequence());
        assertEquals(20, services.inspection().counts().processedEvents());
        assertEquals(1, services.inspection().countOutboxByType("RESYNC_REQUIRED"));

        CanonicalEvent oldFinal = fixture.events().getLast();
        CanonicalEvent finalRevision = new CanonicalEvent("event-020-rev-3",
                oldFinal.schemaVersion(), oldFinal.gameId(), oldFinal.source(),
                oldFinal.providerEventId(), oldFinal.sequence(), 3, oldFinal.type(),
                oldFinal.period(), 1, oldFinal.occurredAt(), oldFinal.teamId(),
                oldFinal.participantIds(), new Score(19, 14), oldFinal.points());
        reconciliation.submit(correctionFixture(fixture, List.of(source(finalRevision))));
        assertEquals("COMPLETED", reconciliation.reconcile(gameId).status());
        assertEquals(1, services.processing().readCheckpoint(gameId).clockMillisRemaining());
        assertEquals(3, JdbcClient.create(dataSource).sql("""
                        SELECT revision FROM reconciliation_selected_events
                        WHERE game_id = :gameId AND generation = 2 AND sequence_number = 20
                        """).param("gameId", gameId).query(Integer.class).single());
    }

    @Test
    void abandonedRebuildingClaimIsRetriedAfterRestartWithoutTwoFinalStates() {
        LoadedFixture fixture = importFixture();
        processAll(services, fixture, false);
        CanonicalEvent old = fixture.events().get(4);
        CanonicalEvent revised = new CanonicalEvent("event-005-rev-2", old.schemaVersion(),
                old.gameId(), old.source(), old.providerEventId(), old.sequence(), 2,
                old.type(), old.period(), old.clockMillisRemaining() - 1, old.occurredAt(),
                old.teamId(), old.participantIds(), old.scoreAfter(), old.points());
        GameReconciliationService first = reconciliation();
        first.submit(correctionFixture(fixture, List.of(source(revised))));
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                        UPDATE game_reconciliations SET status = 'REBUILDING', generation = 1,
                            updated_at = :stale WHERE game_id = :gameId
                        """).param("stale", CLOCK.instant().minusSeconds(600).atOffset(ZoneOffset.UTC))
                .param("gameId", fixture.game().gameId()).update();
        jdbc.sql("""
                        INSERT INTO reconciliation_attempts(id, game_id, generation, status,
                            first_affected_sequence, started_at)
                        VALUES (:id, :gameId, 1, 'REBUILDING', 5, :stale)
                        """).param("id", UUID.randomUUID()).param("gameId", fixture.game().gameId())
                .param("stale", CLOCK.instant().minusSeconds(600).atOffset(ZoneOffset.UTC)).update();
        GameReconciliationService restarted = reconciliation();
        assertEquals("COMPLETED", restarted.reconcile(fixture.game().gameId()).status());
        assertEquals(2, jdbc.sql("""
                        SELECT generation FROM game_reconciliations WHERE game_id = :gameId
                        """).param("gameId", fixture.game().gameId()).query(Integer.class).single());
        assertEquals("RETRIED", jdbc.sql("""
                        SELECT status FROM reconciliation_attempts
                        WHERE game_id = :gameId AND generation = 1
                        """).param("gameId", fixture.game().gameId()).query(String.class).single());
        assertEquals(1, services.inspection().countOutboxByType("RESYNC_REQUIRED"));
    }

    @Test
    void malformedHigherRevisionIsRetainedButValidLowerRevisionRemainsSelected() {
        LoadedFixture fixture = importFixture();
        processAll(services, fixture, false);
        CanonicalEvent old = fixture.events().get(2);
        CanonicalEvent malformed = new CanonicalEvent("event-003-rev-2-invalid",
                old.schemaVersion(), old.gameId(), old.source(), old.providerEventId(),
                old.sequence(), 2, old.type(), old.period(), old.clockMillisRemaining(),
                old.occurredAt(), old.teamId(), old.participantIds(),
                new Score(old.scoreAfter().home() + 2, old.scoreAfter().away()), old.points());
        GameReconciliationService reconciliation = reconciliation();
        reconciliation.submit(correctionFixture(fixture, List.of(source(malformed))));
        assertEquals("UNCHANGED", reconciliation.reconcile(fixture.game().gameId()).status());
        assertEquals(1, JdbcClient.create(dataSource).sql("""
                        SELECT revision FROM reconciliation_selected_events
                        WHERE game_id = :gameId AND generation = 1 AND sequence_number = 3
                        """).param("gameId", fixture.game().gameId()).query(Integer.class).single());
        assertEquals(0, services.inspection().countOutboxByType("RESYNC_REQUIRED"));
        assertEquals(21, services.inspection().counts().canonicalEvents());
    }

    @Test
    void concurrentReconcilerWorkersCommitOnlyOneGeneration() throws Exception {
        LoadedFixture fixture = importFixture();
        processAll(services, fixture, false);
        CanonicalEvent old = fixture.events().get(4);
        CanonicalEvent revised = new CanonicalEvent("event-005-rev-2", old.schemaVersion(),
                old.gameId(), old.source(), old.providerEventId(), old.sequence(), 2,
                old.type(), old.period(), old.clockMillisRemaining() - 1, old.occurredAt(),
                old.teamId(), old.participantIds(), old.scoreAfter(), old.points());
        GameReconciliationService reconciliation = reconciliation();
        reconciliation.submit(correctionFixture(fixture, List.of(source(revised))));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() -> {
                start.await();
                return reconciliation.reconcile(fixture.game().gameId()).status();
            });
            Future<String> second = executor.submit(() -> {
                start.await();
                return reconciliation.reconcile(fixture.game().gameId()).status();
            });
            start.countDown();
            assertEquals(Set.of("COMPLETED", "SKIPPED"), Set.of(first.get(), second.get()));
        }
        assertEquals(1, JdbcClient.create(dataSource).sql("""
                        SELECT count(*) FROM reconciliation_attempts
                        WHERE game_id = :gameId
                        """).param("gameId", fixture.game().gameId()).query(Long.class).single());
        assertEquals(1, services.inspection().countOutboxByType("RESYNC_REQUIRED"));
    }

    @Test
    void pendingWorkQueryUsesItsPartialIndexUnderForcedIndexPlan() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        String plan = transaction.execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", jdbc.sql("""
                            EXPLAIN SELECT game_id FROM game_reconciliations
                            WHERE status = 'PENDING'
                               OR (status = 'REBUILDING' AND updated_at <= now())
                            ORDER BY requested_at, game_id LIMIT 10
                            """).query(String.class).list());
        });
        assertTrue(plan.contains("idx_game_reconciliations_work"), plan);
    }

    @Test
    void changedRuleInvalidatesOldAlertAndCancelsOnlyPendingEmail() {
        LoadedFixture fixture = importFixture();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql("""
                        INSERT INTO application_users(subject, created_at, last_seen_at)
                        VALUES ('owner-a', :now, :now)
                        """).param("now", CLOCK.instant().atOffset(ZoneOffset.UTC)).update();
        new JdbcAlertDeliveryRepository(jdbc).saveSettings(
                "owner-a", true, "owner-a@example.test", CLOCK.instant());
        JdbcAlertRuleRepository rules = new JdbcAlertRuleRepository(jdbc, mapper);
        var created = rules.create("owner-a", "rule-change", new NewAlertRule(
                fixture.game().gameId(), RuleType.PLAYER_POINTS, true, "player_ace",
                null, 10, null, null, null), CLOCK.instant());
        JdbcGameProcessingRepository processing = new JdbcGameProcessingRepository(jdbc, mapper);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc, mapper);
        DurableGameProcessor processor = new DurableGameProcessor(processing, outbox,
                transactions, rules, CLOCK, RuleEngineMetrics.NONE);
        fixture.events().forEach(event -> processor.processEvent(event.eventId()));
        UUID alertId = rules.listOwnedAlerts("owner-a", null, null, 10).getFirst().id();
        UUID deliveryId = jdbc.sql("""
                        SELECT id FROM alert_deliveries WHERE alert_id = :alertId AND channel = 'EMAIL'
                        """).param("alertId", alertId).query(UUID.class).single();
        assertEquals("PENDING", new JdbcAlertDeliveryRepository(jdbc)
                .history("owner-a", null, null, 10).stream()
                .filter(row -> row.id().equals(deliveryId)).findFirst().orElseThrow().status());
        assertTrue(rules.updateEnabled("owner-a", created.rule().id(), false, 1,
                CLOCK.instant()).isPresent());

        CanonicalEvent old = fixture.events().get(4);
        CanonicalEvent revised = new CanonicalEvent("event-005-rev-2", old.schemaVersion(),
                old.gameId(), old.source(), old.providerEventId(), old.sequence(), 2,
                old.type(), old.period(), old.clockMillisRemaining() - 1, old.occurredAt(),
                old.teamId(), old.participantIds(), old.scoreAfter(), old.points());
        GameReconciliationService reconciliation = new GameReconciliationService(jdbc,
                new JdbcFixtureRepository(jdbc, mapper), processing, outbox, rules,
                transactions, CLOCK, mapper);
        reconciliation.submit(correctionFixture(fixture, List.of(source(revised))));
        assertEquals("COMPLETED", reconciliation.reconcile(fixture.game().gameId()).status());
        assertEquals("CORRECTED", rules.listOwnedAlerts("owner-a", null, null, 10)
                .getFirst().status());
        assertEquals("CANCELLED", new JdbcAlertDeliveryRepository(jdbc)
                .history("owner-a", null, null, 10).stream()
                .filter(row -> row.id().equals(deliveryId)).findFirst().orElseThrow().status());
        assertEquals("FAILED", jdbc.sql("""
                        SELECT status FROM delivery_outbox WHERE delivery_id = :deliveryId
                        """).param("deliveryId", deliveryId).query(String.class).single());
    }

    @Test
    void providerReorderCannotSelectOneProviderPlayAtTwoSequences() {
        LoadedFixture fixture = importFixture();
        processAll(services, fixture, false);
        CanonicalEvent moved = fixture.events().get(3);
        CanonicalEvent reordered = new CanonicalEvent("event-003-moved-rev-2",
                moved.schemaVersion(), moved.gameId(), moved.source(),
                fixture.events().get(2).providerEventId(), moved.sequence(), 2,
                moved.type(), moved.period(), moved.clockMillisRemaining(),
                moved.occurredAt(), moved.teamId(), moved.participantIds(),
                moved.scoreAfter(), moved.points());
        GameReconciliationService reconciliation = reconciliation();
        reconciliation.submit(correctionFixture(fixture, List.of(source(reordered))));
        assertEquals("BLOCKED", reconciliation.reconcile(fixture.game().gameId()).status());
        assertEquals("provider_reorder", JdbcClient.create(dataSource).sql("""
                        SELECT last_error_code FROM game_reconciliations WHERE game_id = :gameId
                        """).param("gameId", fixture.game().gameId()).query(String.class).single());
        assertEquals(20, services.processing().readCheckpoint(fixture.game().gameId()).lastAppliedSequence());
    }

    @Test
    void independentGamesCanReconcileConcurrently() throws Exception {
        LoadedFixture fixture = importFixture();
        processAll(services, fixture, false);
        CanonicalEvent old = fixture.events().get(4);
        CanonicalEvent revised = new CanonicalEvent("event-005-rev-2", old.schemaVersion(),
                old.gameId(), old.source(), old.providerEventId(), old.sequence(), 2,
                old.type(), old.period(), old.clockMillisRemaining() - 1, old.occurredAt(),
                old.teamId(), old.participantIds(), old.scoreAfter(), old.points());
        GameReconciliationService reconciliation = reconciliation();
        reconciliation.submit(correctionFixture(fixture, List.of(source(revised))));

        String secondGameId = "game-other";
        List<LoadedSourceEvent> secondEvents = new ArrayList<>();
        for (CanonicalEvent original : fixture.events().subList(0, 3)) {
            secondEvents.add(source(new CanonicalEvent(
                    secondGameId + "-event-" + original.sequence(), original.schemaVersion(),
                    secondGameId, original.source(),
                    secondGameId + ":" + original.providerEventId(), original.sequence(),
                    original.revision(), original.type(), original.period(),
                    original.clockMillisRemaining(), original.occurredAt(), original.teamId(),
                    original.participantIds(), original.scoreAfter(), original.points())));
        }
        reconciliation.submit(new LoadedFixture(1, "other-game", "concurrent game",
                fixture.provenance(), new FixtureGame(secondGameId,
                        fixture.game().homeTeamId(), fixture.game().awayTeamId()), secondEvents));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() -> {
                start.await();
                return reconciliation.reconcile(fixture.game().gameId()).status();
            });
            Future<String> second = executor.submit(() -> {
                start.await();
                return reconciliation.reconcile(secondGameId).status();
            });
            start.countDown();
            assertEquals("COMPLETED", first.get());
            assertEquals("COMPLETED", second.get());
        }
        assertEquals(20, services.processing().readCheckpoint(fixture.game().gameId()).lastAppliedSequence());
        assertEquals(3, services.processing().readCheckpoint(secondGameId).lastAppliedSequence());
    }

    private static LoadedFixture correctionFixture(LoadedFixture original, List<LoadedSourceEvent> events) {
        return new LoadedFixture(original.fixtureSchemaVersion(), "correction",
                original.description(), original.provenance(), original.game(), events);
    }

    private static LoadedSourceEvent source(CanonicalEvent event) {
        String raw = "{\"eventId\":\"" + event.eventId() + "\"}";
        return new LoadedSourceEvent(event, raw, sha256(raw));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static GameReconciliationService reconciliation() {
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new GameReconciliationService(jdbc, new JdbcFixtureRepository(jdbc, mapper),
                new JdbcGameProcessingRepository(jdbc, mapper), new JdbcOutboxRepository(jdbc, mapper),
                new FixedAlertRuleSource(List.of(new PlayerMilestoneRule(
                        "milestone-player-ace-10", "player_ace", 10))), transactions, CLOCK, mapper);
    }

    private LoadedFixture importFixture() {
        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        services.ingestion().importFixture(fixture);
        return fixture;
    }

    private static RunCounts processAll(Services services, LoadedFixture fixture, boolean duplicates) {
        List<String> eventIds = new ArrayList<>(services.processing().listEventIds(fixture.game().gameId()));
        if (duplicates) {
            eventIds.add(4, fixture.events().get(3).eventId());
            eventIds.add(12, fixture.events().get(10).eventId());
        }
        long accepted = 0;
        long suppressed = 0;
        for (String eventId : eventIds) {
            if (services.processor().processEvent(eventId).accepted()) {
                accepted++;
            } else {
                suppressed++;
            }
        }
        return new RunCounts(accepted, suppressed);
    }

    private static Services createServices() {
        ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcFixtureRepository fixtures = new JdbcFixtureRepository(jdbc, objectMapper);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc, objectMapper);
        JdbcGameProcessingRepository processing =
                new JdbcGameProcessingRepository(jdbc, objectMapper);
        JdbcInspectionRepository inspection = new JdbcInspectionRepository(jdbc);
        FixtureIngestionService ingestion =
                new FixtureIngestionService(fixtures, outbox, transactions, CLOCK);
        DurableGameProcessor processor = new DurableGameProcessor(
                processing,
                outbox,
                transactions,
                List.of(new PlayerMilestoneRule("milestone-player-ace-10", "player_ace", 10)),
                CLOCK);
        return new Services(fixtures, processing, inspection, ingestion, processor);
    }

    private record RunCounts(long accepted, long suppressed) {}

    private record Services(
            JdbcFixtureRepository fixtures,
            JdbcGameProcessingRepository processing,
            JdbcInspectionRepository inspection,
            FixtureIngestionService ingestion,
            DurableGameProcessor processor) {}
}
