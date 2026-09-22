package com.courtpulse.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.courtpulse.domain.alert.PlayerMilestoneRule;
import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.replay.StateChecksum;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.testkit.SyntheticFixtureResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
