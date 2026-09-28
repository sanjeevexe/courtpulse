package com.courtpulse.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.game.GameStatus;
import com.courtpulse.providers.balldontlie.BallDontLiePlayMapper;
import com.courtpulse.providers.live.ProviderGame;
import com.courtpulse.providers.live.ProviderLifecycle;
import com.courtpulse.providers.live.ProviderPlay;
import com.courtpulse.providers.live.ProviderTeam;
import com.courtpulse.testkit.SyntheticFixtureResources;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
class ProviderIngestionIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final String GAME_ID = "bdl-game-990001";
    private static final String ADA = "bdl-player-9000101";
    private static final String BO = "bdl-player-9000102";
    private static DataSource dataSource;

    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final MutableClock clock = new MutableClock();
    private JsonNode fixture;
    private BallDontLiePlayMapper mapper;
    private JdbcClient jdbc;
    private JdbcProviderRepository providers;
    private ProviderIngestionService ingestion;
    private GameReconciliationService reconciliation;
    private JdbcGameProcessingRepository processing;
    private DurableGameProcessor processor;

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
                    application_users, outbox CASCADE
                """).update();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcFixtureRepository fixtures = new JdbcFixtureRepository(jdbc, json);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc, json);
        processing = new JdbcGameProcessingRepository(jdbc, json);
        JdbcAlertRuleRepository rules = new JdbcAlertRuleRepository(jdbc, json);
        reconciliation = new GameReconciliationService(
                jdbc, fixtures, processing, outbox, rules, transactions, clock, json);
        providers = new JdbcProviderRepository(jdbc);
        ingestion = new ProviderIngestionService(providers, fixtures, outbox, reconciliation, transactions, clock);
        processor = new DurableGameProcessor(processing, outbox, transactions, rules, clock,
                new MicrometerRuleEngineMetrics(new SimpleMeterRegistry()));
        fixture = json.readTree(SyntheticFixtureResources.ballDontLieOvertimeGame());
        mapper = new BallDontLiePlayMapper(json);
    }

    @Test
    void livePollsReachOvertimeFinalAndACorrectionRewritesAlertsExactlyOnce() {
        ProviderGame live = game(ProviderLifecycle.LIVE, "3rd Qtr");
        ingestion.recordDiscovery(List.of(live));
        createRule("fan-bo", BO, 8);
        createRule("fan-ada", ADA, 31);

        var first = ingestion.ingest(live, plays(fixture.path("plays"), 60));
        assertEquals(60, first.newEvents());
        processAll();
        assertEquals(60, processing.readCheckpoint(GAME_ID).lastAppliedSequence());

        var repeat = ingestion.ingest(live, plays(fixture.path("plays"), 60));
        assertEquals(new ProviderIngestionService.FeedResult(0, 0, 60, 0, false), repeat);
        assertEquals(60, count("SELECT count(*) FROM outbox WHERE destination = 'GAME_EVENTS'"));

        ProviderGame fin = game(ProviderLifecycle.FINAL, "Final");
        assertTrue(ingestion.needsPlays(fin), "a provider-final game is polled until CourtPulse is final");
        var remainder = ingestion.ingest(fin, plays(fixture.path("plays"), Integer.MAX_VALUE));
        assertEquals(107, remainder.newEvents());
        processAll();
        GameState finalState = processing.readCheckpoint(GAME_ID);
        JsonNode expected = fixture.path("expected");
        assertEquals(GameStatus.FINAL, finalState.status());
        assertEquals(5, finalState.period());
        assertEquals(expected.path("finalScore").path("home").asInt(), finalState.homeScore());
        assertEquals(expected.path("finalScore").path("away").asInt(), finalState.awayScore());
        assertEquals(9, finalState.pointsFor(BO));
        assertEquals(30, finalState.pointsFor(ADA));
        assertFalse(ingestion.needsPlays(fin), "just polled: not due for a correction refetch yet");
        clock.advance(ProviderIngestionService.FinalRefetch.DEFAULT.interval().plusSeconds(1));
        assertTrue(ingestion.needsPlays(fin), "completed games are re-polled for provider corrections");
        assertEquals("CREATED", alertStatus("fan-bo"));
        assertEquals(0, count("SELECT count(*) FROM alert_instances WHERE rule_id = " + ruleIdSql("fan-ada")));
        assertEquals(0, count("SELECT count(*) FROM alert_rules WHERE game_id = '" + GAME_ID
                + "' AND owner_subject IS NULL"), "provider games never get the synthetic demo rule");

        List<JsonNode> corrected = corrected();
        var correction = ingestion.ingest(fin, plays(corrected, Integer.MAX_VALUE));
        assertEquals(1, correction.corrections());
        assertEquals(0, correction.newEvents());
        reconciliation.reconcileAvailable(10);
        GameState rebuilt = processing.readCheckpoint(GAME_ID);
        assertEquals(6, rebuilt.pointsFor(BO));
        assertEquals(33, rebuilt.pointsFor(ADA));
        assertEquals(finalState.homeScore(), rebuilt.homeScore());
        assertEquals("CORRECTED", alertStatus("fan-bo"));
        assertEquals("CREATED", alertStatus("fan-ada"));

        var again = ingestion.ingest(fin, plays(corrected, Integer.MAX_VALUE));
        assertEquals(0, again.corrections(), "the corrected body is now the latest revision");
        var reverted = ingestion.ingest(fin, plays(fixture.path("plays"), Integer.MAX_VALUE));
        assertEquals(0, reverted.corrections());
        assertEquals(1, reverted.rejected());
        assertEquals(1, count("SELECT count(*) FROM provider_data_incidents WHERE reason = 'REVERTED_CONTENT'"));
        assertEquals(2, count("SELECT count(*) FROM canonical_events WHERE provider_event_id = '990001:"
                + fixture.path("correction").path("order").asInt() + "'"));

        List<JsonNode> missingOne = new ArrayList<>(corrected);
        missingOne.remove(99);
        ingestion.ingest(fin, plays(missingOne, Integer.MAX_VALUE));
        assertEquals(1, count("SELECT count(*) FROM provider_data_incidents WHERE reason = 'PLAY_REMOVED'"));

        assertEquals("FINAL|166|0|false", jdbc.sql("""
                        SELECT lifecycle || '|' || observed_plays || '|' || consecutive_failures
                            || '|' || (last_success_at IS NULL)
                        FROM provider_game_observations WHERE game_id = :gameId
                        """).param("gameId", GAME_ID).query(String.class).single());
        clock.advance(ProviderIngestionService.FinalRefetch.DEFAULT.window().plusSeconds(1));
        assertFalse(ingestion.needsPlays(fin), "correction polling stops after the window");
        assertEquals("Harbor City Herons|HCH", jdbc.sql("SELECT name || '|' || abbreviation FROM teams "
                + "WHERE id = 'bdl-team-90001'").query(String.class).single());
    }

    @Test
    void unmappablePlayIsRecordedOnceWhileTheRestOfTheFeedIsIngested() {
        ProviderGame live = game(ProviderLifecycle.LIVE, "1st Qtr");
        List<JsonNode> feed = new ArrayList<>();
        fixture.path("plays").forEach(feed::add);
        feed = new ArrayList<>(feed.subList(0, 20));
        ((ObjectNode) feed.get(9)).put("clock", "99:99");

        var first = ingestion.ingest(live, plays(feed, Integer.MAX_VALUE));
        var second = ingestion.ingest(live, plays(feed, Integer.MAX_VALUE));

        assertEquals(19, first.newEvents());
        assertEquals(1, first.rejected());
        assertEquals(1, second.rejected());
        assertEquals(1, count("SELECT count(*) FROM provider_data_incidents WHERE reason = 'MAPPING_REJECTED'"
                + " AND detail = 'invalid_clock' AND payload IS NOT NULL"));
        processAll();
        assertEquals(9, processing.readCheckpoint(GAME_ID).lastAppliedSequence(),
                "the rejected play is a gap: later plays wait instead of skipping it");
    }

    @Test
    void finalWithoutEndGamePlayIsDerivedOnlyAfterTheGracePeriod() {
        ProviderGame fin = game(ProviderLifecycle.FINAL, "Final");
        List<JsonNode> feed = new ArrayList<>();
        fixture.path("plays").forEach(feed::add);
        feed.removeLast();

        var first = ingestion.ingest(fin, plays(feed, Integer.MAX_VALUE));
        assertFalse(first.derivedFinal());
        processAll();
        clock.advance(ProviderIngestionService.DERIVED_FINAL_GRACE.minusSeconds(1));
        assertFalse(ingestion.ingest(fin, plays(feed, Integer.MAX_VALUE)).derivedFinal());
        clock.advance(Duration.ofSeconds(2));
        assertTrue(ingestion.ingest(fin, plays(feed, Integer.MAX_VALUE)).derivedFinal());
        assertFalse(ingestion.ingest(fin, plays(feed, Integer.MAX_VALUE)).derivedFinal(), "derived once");
        processAll();
        GameState state = processing.readCheckpoint(GAME_ID);
        assertEquals(GameStatus.FINAL, state.status());
        assertEquals(167, state.lastAppliedSequence());
    }

    @Test
    void discoveryShowsScheduledGamesAndFailuresNeverRefreshFreshness() {
        ProviderGame scheduled = game(ProviderLifecycle.SCHEDULED, "7:30 pm ET");
        ingestion.recordDiscovery(List.of(scheduled));
        assertFalse(ingestion.needsPlays(scheduled));
        assertEquals("SCHEDULED", jdbc.sql("SELECT status FROM game_checkpoints WHERE game_id = :id")
                .param("id", GAME_ID).query(String.class).single());
        assertEquals(1, count("SELECT count(*) FROM games WHERE provider_game_id = '990001' AND scheduled_at IS NOT NULL"));

        ingestion.recordFailure(game(ProviderLifecycle.LIVE, "1st Qtr"), "http_5xx");
        ingestion.recordFailure(game(ProviderLifecycle.LIVE, "1st Qtr"), "timeout");
        assertEquals("timeout|2|true", jdbc.sql("""
                        SELECT last_error_code || '|' || consecutive_failures || '|' || (last_success_at IS NULL)
                        FROM provider_game_observations WHERE game_id = :id
                        """).param("id", GAME_ID).query(String.class).single());
    }

    private void processAll() {
        for (int pass = 0; pass < 3; pass++) {
            processing.listEventIds(GAME_ID).forEach(processor::processEvent);
            reconciliation.reconcileAvailable(10);
        }
    }

    private List<ProviderPlay> plays(Iterable<JsonNode> nodes, int limit) {
        ProviderGame shape = game(ProviderLifecycle.LIVE, "live");
        List<ProviderPlay> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (result.size() >= limit) {
                break;
            }
            result.add(mapper.map(node, shape));
        }
        return result;
    }

    private List<JsonNode> corrected() {
        int order = fixture.path("correction").path("order").asInt();
        List<JsonNode> result = new ArrayList<>();
        fixture.path("plays").forEach(play -> result.add(play.path("order").asInt() == order
                ? fixture.path("correction").path("play") : play));
        return result;
    }

    private ProviderGame game(ProviderLifecycle lifecycle, String status) {
        JsonNode game = fixture.path("game");
        return new ProviderGame("balldontlie", "990001", GAME_ID,
                new ProviderTeam("bdl-team-90001", "90001", "Harbor City Herons", "HCH"),
                new ProviderTeam("bdl-team-90002", "90002", "Summit Valley Sentinels", "SVS"),
                Instant.parse(game.path("datetime").asText()), status, lifecycle);
    }

    private void createRule(String requestId, String playerId, int threshold) {
        jdbc.sql("""
                INSERT INTO application_users(subject, created_at, last_seen_at)
                VALUES ('subject-' || :requestId, now(), now())
                """).param("requestId", requestId).update();
        jdbc.sql("""
                INSERT INTO alert_rules(id, owner_subject, game_id, rule_type, enabled, player_id,
                    points_threshold, client_request_id, request_fingerprint, version, created_at, updated_at)
                VALUES (gen_random_uuid(), 'subject-' || :requestId, :gameId, 'PLAYER_POINTS', true,
                    :playerId, :threshold, :requestId, repeat('c', 64), 1, now(), now())
                """).param("requestId", requestId).param("gameId", GAME_ID)
                .param("playerId", playerId).param("threshold", threshold).update();
    }

    private String alertStatus(String requestId) {
        return jdbc.sql("SELECT status FROM alert_instances WHERE rule_id = " + ruleIdSql(requestId))
                .query(String.class).single();
    }

    private static String ruleIdSql(String requestId) {
        return "(SELECT id::text FROM alert_rules WHERE client_request_id = '" + requestId + "')";
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-15T03:00:00Z");

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
