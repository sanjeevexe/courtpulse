package com.courtpulse.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.game.GameStatus;
import com.courtpulse.providers.live.ProviderGame;
import com.courtpulse.providers.live.ProviderLifecycle;
import com.courtpulse.providers.nba.NbaPlayByPlayCsv;
import com.courtpulse.providers.nba.NbaReplayGame;
import com.courtpulse.testkit.SyntheticFixtureResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
class ReplayIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final String NBA_GAME = "0049900101";
    private static DataSource dataSource;

    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final MutableClock clock = new MutableClock();
    private JdbcClient jdbc;
    private JdbcReplayRepository replays;
    private NbaReplayProvider provider;
    private ProviderIngestionService ingestion;
    private JdbcGameProcessingRepository processing;
    private DurableGameProcessor processor;
    private GameReconciliationService reconciliation;
    private NbaReplayGame fixture;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        postgres.setURL(POSTGRES.getJdbcUrl());
        postgres.setUser(POSTGRES.getUsername());
        postgres.setPassword(POSTGRES.getPassword());
        dataSource = postgres;
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void services() throws Exception {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE TABLE games, teams, players, provider_health, provider_data_incidents,
                    application_users, outbox, replay_catalog, replay_players CASCADE
                """).update();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcFixtureRepository fixtures = new JdbcFixtureRepository(jdbc, json);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc, json);
        processing = new JdbcGameProcessingRepository(jdbc, json);
        JdbcAlertRuleRepository rules = new JdbcAlertRuleRepository(jdbc, json);
        reconciliation = new GameReconciliationService(
                jdbc, fixtures, processing, outbox, rules, transactions, clock, json);
        ingestion = new ProviderIngestionService(
                new JdbcProviderRepository(jdbc), fixtures, outbox, reconciliation, transactions, clock);
        processor = new DurableGameProcessor(processing, outbox, transactions, rules, clock,
                new MicrometerRuleEngineMetrics(new SimpleMeterRegistry()));
        replays = new JdbcReplayRepository(jdbc, json);
        provider = new NbaReplayProvider(replays, json, clock);
        fixture = NbaReplayGame.from(NbaPlayByPlayCsv.read(new StringReader(new String(
                SyntheticFixtureResources.nbaReplayGame(), StandardCharsets.UTF_8))).get(NBA_GAME));
        replays.saveGame("fixture", fixture, clock.instant());
    }

    @Test
    void catalogKeepsTeamsScoresAndPlayersAndReimportIsIdempotent() {
        replays.saveGame("fixture", fixture, clock.instant());

        List<JdbcReplayRepository.CatalogGame> catalog = replays.catalog(null, null, 50);
        assertEquals(1, catalog.size());
        JdbcReplayRepository.CatalogGame game = catalog.getFirst();
        assertEquals("HCH", game.home().tricode());
        assertEquals(16, game.homeScore());
        assertEquals(12, game.awayScore());
        assertEquals(41, game.actionCount());
        assertEquals(1, replays.catalog("SVS", null, 50).size());
        assertEquals(0, replays.catalog("BOS", null, 50).size());
        assertEquals(0, replays.catalog(null, NBA_GAME, 50).size(), "nothing older than the only game");
        assertEquals("A. Lane", replays.playerName(101).orElseThrow());
        assertEquals(41, replays.releasedActions(NBA_GAME, Long.MAX_VALUE).size());
    }

    @Test
    void replayTimeFollowsSpeedPauseResumeAndSpeedChanges() {
        ReplaySession session = replays.createSession(NBA_GAME, 10, "fan-a", clock.instant()).orElseThrow();
        assertEquals("nba-replay-0049900101-1", session.gameId());

        clock.advance(Duration.ofSeconds(6));
        assertEquals(60_000, current(session).elapsedAt(clock.instant()));
        ReplaySession running = current(session);
        assertTrue(replays.update(running, running.paused(clock.instant())));

        clock.advance(Duration.ofMinutes(5));
        ReplaySession paused = current(session);
        assertEquals(60_000, paused.elapsedAt(clock.instant()), "paused replays do not advance");
        assertTrue(replays.update(paused, paused.resumed(clock.instant())));
        clock.advance(Duration.ofSeconds(1));
        ReplaySession resumed = current(session);
        assertEquals(70_000, resumed.elapsedAt(clock.instant()));

        assertTrue(replays.update(resumed, resumed.withSpeed(60, clock.instant())));
        clock.advance(Duration.ofSeconds(1));
        assertEquals(130_000, current(session).elapsedAt(clock.instant()));
        assertFalse(replays.update(resumed, resumed.paused(clock.instant())), "stale versions are refused");

        ReplaySession second = replays.createSession(NBA_GAME, 30, "fan-b", clock.instant()).orElseThrow();
        assertEquals("nba-replay-0049900101-2", second.gameId(), "every run is a separate game");
        assertEquals(2, replays.countUnfinished(null));
        assertEquals(1, replays.countUnfinished("fan-a"));
        assertTrue(replays.createSession("0000000000", 10, "fan-a", clock.instant()).isEmpty());
    }

    @Test
    void aReplayFlowsThroughIngestionAndProcessingToTheRealFinalWithAnAlert() {
        ReplaySession session = replays.createSession(NBA_GAME, 30, "fan-a", clock.instant()).orElseThrow();

        ProviderGame game = provider.discover(List.of()).getFirst();
        assertEquals(ProviderLifecycle.LIVE, game.lifecycle());
        assertEquals("HCH", game.homeTeam().name(), "fictional codes fall back to the code as the name");
        ingestion.recordDiscovery(List.of(game));
        createRule(session.gameId(), "nba-player-101", 10);
        ingestion.ingest(game, provider.plays(game));
        processAll(session.gameId());
        GameState early = processing.readCheckpoint(session.gameId());
        assertEquals(GameStatus.LIVE, early.status());
        assertTrue(early.lastAppliedSequence() >= 1 && early.lastAppliedSequence() < 41);

        for (int poll = 0; poll < 40 && provider.discover(List.of()).getFirst().lifecycle() != ProviderLifecycle.FINAL;
                poll++) {
            clock.advance(Duration.ofSeconds(2));
            ProviderGame polled = provider.discover(List.of()).getFirst();
            ingestion.ingest(polled, provider.plays(polled));
            processAll(session.gameId());
        }
        ProviderGame fin = provider.discover(List.of()).getFirst();
        assertEquals(ProviderLifecycle.FINAL, fin.lifecycle());
        ingestion.ingest(fin, provider.plays(fin));
        processAll(session.gameId());

        GameState finalState = processing.readCheckpoint(session.gameId());
        assertEquals(GameStatus.FINAL, finalState.status());
        assertEquals(41, finalState.lastAppliedSequence());
        assertEquals(16, finalState.homeScore());
        assertEquals(12, finalState.awayScore());
        assertEquals(16, finalState.pointsFor("nba-player-101"));
        assertEquals(ReplaySession.Status.FINISHED, current(session).status());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM alert_instances WHERE game_id = :id")
                .param("id", session.gameId()).query(Long.class).single());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM provider_data_incidents").query(Long.class).single());

        // The ingestor resolves player names from the provider; alerts and emails then read as people expect.
        for (String playerId : ingestion.unknownPlayerIds(provider.source(), 20)) {
            provider.player(provider.providerPlayerId(playerId).orElseThrow())
                    .ifPresent(player -> ingestion.savePlayer(provider.source(), player));
        }
        JdbcDisplayNames.Names names = new JdbcDisplayNames(jdbc).lookup(
                List.of("nba-player-101"), List.of("nba-team-9001"), List.of(session.gameId()));
        assertEquals("A. Lane", names.player("nba-player-101"));
        assertEquals("SVS at HCH", names.game(session.gameId()));
        assertEquals("A. Lane reached 10 points", AlertWording.title(com.courtpulse.domain.alert.RuleType.PLAYER_POINTS,
                java.util.Map.of("playerId", "nba-player-101", "threshold", "10"), "stored", names));
    }

    @Test
    void skippingToTheEndReleasesEveryPlay() {
        ReplaySession session = replays.createSession(NBA_GAME, 1, "fan-a", clock.instant()).orElseThrow();
        assertTrue(replays.update(session, session.finished(fixture.durationMillis(), clock.instant())));

        ProviderGame game = provider.discover(List.of()).getFirst();
        assertEquals(ProviderLifecycle.FINAL, game.lifecycle());
        assertEquals(41, provider.plays(game).size());
        assertEquals("nba-player-101", provider.player("101").orElseThrow().playerId());
        assertEquals("101", provider.providerPlayerId("nba-player-101").orElseThrow());
        assertTrue(provider.player("x").isEmpty());
    }

    private ReplaySession current(ReplaySession session) {
        return replays.session(session.id()).orElseThrow();
    }

    private void processAll(String gameId) {
        for (int pass = 0; pass < 3; pass++) {
            processing.listEventIds(gameId).forEach(processor::processEvent);
            reconciliation.reconcileAvailable(10);
        }
    }

    private void createRule(String gameId, String playerId, int threshold) {
        jdbc.sql("INSERT INTO application_users(subject, created_at, last_seen_at) VALUES ('fan-a', now(), now())")
                .update();
        jdbc.sql("""
                INSERT INTO alert_rules(id, owner_subject, game_id, rule_type, enabled, player_id,
                    points_threshold, client_request_id, request_fingerprint, version, created_at, updated_at)
                VALUES (gen_random_uuid(), 'fan-a', :gameId, 'PLAYER_POINTS', true, :playerId, :threshold,
                    'replay-rule', repeat('c', 64), 1, now(), now())
                """).param("gameId", gameId).param("playerId", playerId).param("threshold", threshold).update();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
