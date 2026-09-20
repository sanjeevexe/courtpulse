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
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes the durable contract on a real PostgreSQL process when Docker is unavailable locally.
 * The Testcontainers suite remains the CI and Docker-enabled verification authority.
 */
class EmbeddedPostgresLocalVerificationTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T01:00:00Z"), ZoneOffset.UTC);
    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private Services services;

    @BeforeAll
    static void startPostgresAndMigrate() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void reset() {
        services = createServices();
        services.fixtures().resetAll();
    }

    @Test
    void durableReplayRollbackRestartAndReprocessingAreCorrect() {
        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        assertEquals(new ImportResult(20, 20, 20), services.ingestion().importFixture(fixture));
        assertEquals(new ImportResult(0, 0, 0), services.ingestion().importFixture(fixture));

        List<String> eventIds = services.processing().listEventIds(fixture.game().gameId());
        eventIds.subList(0, 10).forEach(eventId -> services.processor().processEvent(eventId));
        String milestoneEvent = eventIds.get(10);
        DatabaseCounts beforeFailure = services.inspection().counts();
        GameState checkpointBeforeFailure = services.processing().readCheckpoint(fixture.game().gameId());
        assertThrows(
                SimulatedProcessingFailureException.class,
                () -> services.processor().processEvent(milestoneEvent, FailureMode.BEFORE_COMMIT));
        assertEquals(10, services.inspection().checkpointVersion(fixture.game().gameId()));
        assertEquals(
                checkpointBeforeFailure,
                services.processing().readCheckpoint(fixture.game().gameId()));
        assertEquals(beforeFailure.processedEvents(), services.inspection().counts().processedEvents());
        assertEquals(0, services.inspection().counts().alertInstances());
        assertEquals(beforeFailure.outboxRecords(), services.inspection().counts().outboxRecords());

        for (String eventId : eventIds.subList(10, eventIds.size())) {
            assertTrue(services.processor().processEvent(eventId).accepted());
        }
        GameState finalState = services.processing().readCheckpoint(fixture.game().gameId());
        String checksum = StateChecksum.sha256(finalState);

        assertEquals(18, finalState.homeScore());
        assertEquals(14, finalState.awayScore());
        assertEquals(13, finalState.pointsFor("player_ace"));
        assertEquals("06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca", checksum);
        assertEquals(1, services.processing().listAlerts(fixture.game().gameId()).size());
        assertEquals(41, services.inspection().counts().outboxRecords());

        Services restarted = createServices();
        for (String eventId : restarted.processing().listEventIds(fixture.game().gameId())) {
            assertFalse(restarted.processor().processEvent(eventId).accepted());
        }
        assertEquals(
                checksum,
                StateChecksum.sha256(restarted.processing().readCheckpoint(fixture.game().gameId())));
        assertEquals(1, restarted.processing().listAlerts(fixture.game().gameId()).size());
        assertEquals(20, restarted.inspection().checkpointVersion(fixture.game().gameId()));
    }

    @Test
    void committedWorkSurvivesFailureAndRedelivery() {
        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        services.ingestion().importFixture(fixture);
        String firstEvent = fixture.events().getFirst().eventId();

        assertThrows(
                SimulatedProcessingFailureException.class,
                () -> services.processor().processEvent(firstEvent, FailureMode.AFTER_COMMIT));
        assertEquals(1, services.inspection().checkpointVersion(fixture.game().gameId()));
        assertEquals(1, services.inspection().counts().processedEvents());
        assertFalse(createServices().processor().processEvent(firstEvent).accepted());
        assertEquals(1, services.inspection().checkpointVersion(fixture.game().gameId()));
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

    private record Services(
            JdbcFixtureRepository fixtures,
            JdbcGameProcessingRepository processing,
            JdbcInspectionRepository inspection,
            FixtureIngestionService ingestion,
            DurableGameProcessor processor) {}
}
