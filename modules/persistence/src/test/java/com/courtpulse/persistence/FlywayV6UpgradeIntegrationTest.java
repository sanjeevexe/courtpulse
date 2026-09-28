package com.courtpulse.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.courtpulse.testkit.SyntheticFixtureResources;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class FlywayV6UpgradeIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11-alpine");

    @Test
    void upgradesV1ThroughV5DataAndBackfillsTheStableSystemRule() {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        JdbcClient administrator = JdbcClient.create(baseDataSource());
        administrator.sql("CREATE SCHEMA " + schema).update();
        try {
            DataSource dataSource = dataSource(schema);
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .target("5")
                    .load()
                    .migrate();
            JdbcClient jdbc = JdbcClient.create(dataSource);
            jdbc.sql("""
                        INSERT INTO games(id, source, home_team_id, away_team_id, status, created_at, updated_at)
                        VALUES ('existing-game', 'fixture', 'home', 'away', 'SCHEDULED', now(), now())
                        """).update();
            jdbc.sql("""
                        INSERT INTO game_checkpoints(
                            game_id, state_version, status, period, clock_millis_remaining,
                            home_score, away_score, last_sequence, player_points,
                            recent_event_ids, state_checksum, updated_at)
                        VALUES ('existing-game', 0, 'SCHEDULED', 0, 0, 0, 0, 0,
                                '{}'::jsonb, '[]'::jsonb, NULL, now())
                        """).update();
            jdbc.sql("""
                        INSERT INTO application_users(subject, created_at, last_seen_at)
                        VALUES ('existing-user', now(), now())
                        """).update();
            jdbc.sql("""
                        INSERT INTO followed_games(user_subject, game_id, followed_at)
                        VALUES ('existing-user', 'existing-game', now())
                        """).update();

            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();

            assertEquals(1L, jdbc.sql("SELECT count(*) FROM games WHERE id = 'existing-game'")
                    .query(Long.class).single());
            assertEquals(1L, jdbc.sql("SELECT count(*) FROM followed_games WHERE user_subject = 'existing-user'")
                    .query(Long.class).single());
        assertEquals(1L, jdbc.sql("""
                        SELECT count(*) FROM alert_rules
                        WHERE game_id = 'existing-game' AND owner_subject IS NULL
                          AND rule_type = 'PLAYER_POINTS' AND player_id = 'player_ace'
                          AND points_threshold = 10
                        """).query(Long.class).single());
        assertEquals(SystemDemoRuleId.forGame("existing-game"),
                jdbc.sql("SELECT id FROM alert_rules WHERE game_id = 'existing-game'")
                        .query(UUID.class).single());
        assertEquals(0L, jdbc.sql("""
                        SELECT points FROM game_scoring_runs WHERE game_id = 'existing-game'
                        """).query(Long.class).single());
        } finally {
            administrator.sql("DROP SCHEMA " + schema + " CASCADE").update();
        }
    }

    @Test
    void upgradesMilestoneEightSchemaToDeliverySchemaWithoutLosingOwners() {
        String schema = "delivery_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        JdbcClient administrator = JdbcClient.create(baseDataSource());
        administrator.sql("CREATE SCHEMA " + schema).update();
        try {
            DataSource dataSource = dataSource(schema);
            Flyway.configure().dataSource(dataSource).schemas(schema).target("6").load().migrate();
            JdbcClient jdbc = JdbcClient.create(dataSource);
            jdbc.sql("""
                    INSERT INTO application_users(subject, created_at, last_seen_at)
                    VALUES ('upgrade-owner', now(), now())
                    """).update();
            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();
            assertEquals(1L, jdbc.sql("SELECT count(*) FROM application_users WHERE subject = 'upgrade-owner'")
                    .query(Long.class).single());
            assertEquals(1L, jdbc.sql("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = :schema AND table_name = 'delivery_attempts'
                    """).param("schema", schema).query(Long.class).single());
            assertEquals(0L, new JdbcDeliveryWorkRepository(jdbc)
                    .operations(java.time.Instant.now()).backlog());
        } finally {
            administrator.sql("DROP SCHEMA " + schema + " CASCADE").update();
        }
    }

    @Test
    void upgradesPopulatedV7CheckpointAndEventHistoryToV8WithoutRewritingThem() {
        String schema = "correction_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        JdbcClient administrator = JdbcClient.create(baseDataSource());
        administrator.sql("CREATE SCHEMA " + schema).update();
        try {
            DataSource dataSource = dataSource(schema);
            Flyway.configure().dataSource(dataSource).schemas(schema).target("7").load().migrate();
            JdbcClient jdbc = JdbcClient.create(dataSource);
            var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
            var fixtures = new JdbcFixtureRepository(jdbc, mapper);
            var processing = new JdbcGameProcessingRepository(jdbc, mapper);
            var fixture = SyntheticFixtureResources.loadMilestoneGame();
            var now = Clock.systemUTC().instant();
            fixtures.ensureGame(fixture.sourceEvents().getFirst().canonicalEvent().source(),
                    fixture.game(), now);
            for (var item : fixture.sourceEvents()) {
                var raw = fixtures.insertOrObserveRaw(item, now);
                insertV7Canonical(jdbc, item.canonicalEvent(), raw.id(), now);
            }
            // Current repositories may use later columns, so compare durable rows directly.
            String before = durableRows(jdbc);

            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();

            assertEquals(before, durableRows(jdbc));
            assertEquals(0L, processing.readCheckpoint(fixture.game().gameId()).lastAppliedSequence());
            assertEquals(20L, jdbc.sql("SELECT count(*) FROM canonical_events")
                    .query(Long.class).single());
            assertEquals(20L, jdbc.sql("SELECT count(*) FROM raw_provider_payloads")
                    .query(Long.class).single());
            assertEquals(0L, jdbc.sql("SELECT count(*) FROM processed_events")
                    .query(Long.class).single());
            assertEquals(0L, jdbc.sql("SELECT count(*) FROM game_reconciliations")
                    .query(Long.class).single());
            assertEquals(1L, jdbc.sql("""
                            SELECT count(*) FROM information_schema.tables
                            WHERE table_schema = :schema AND table_name = 'reconciliation_selected_events'
                            """).param("schema", schema).query(Long.class).single());
        } finally {
            administrator.sql("DROP SCHEMA " + schema + " CASCADE").update();
        }
    }

    /** The V1-V7 canonical_events column set; deliberately independent of current repositories. */
    private static void insertV7Canonical(JdbcClient jdbc, com.courtpulse.domain.event.CanonicalEvent event,
            UUID rawPayloadId, java.time.Instant now) {
        jdbc.sql("""
                        INSERT INTO canonical_events (
                            event_id, schema_version, source, game_id, provider_event_id,
                            sequence_number, revision, event_type, period, clock_millis_remaining,
                            occurred_at, team_id, participant_ids, home_score, away_score, points,
                            raw_payload_id, canonical_payload, created_at)
                        VALUES (:eventId, 1, :source, :gameId, :providerEventId, :sequence, :revision,
                            :type, :period, :clock, :occurredAt, :teamId, CAST(:participants AS JSONB),
                            :home, :away, :points, :rawId, '{}'::JSONB, :now)
                        """)
                .param("eventId", event.eventId()).param("source", event.source())
                .param("gameId", event.gameId()).param("providerEventId", event.providerEventId())
                .param("sequence", event.sequence()).param("revision", event.revision())
                .param("type", event.type().name()).param("period", event.period())
                .param("clock", event.clockMillisRemaining())
                .param("occurredAt", java.time.OffsetDateTime.ofInstant(event.occurredAt(), java.time.ZoneOffset.UTC))
                .param("teamId", event.teamId())
                .param("participants", "[" + event.participantIds().stream()
                        .map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(",")) + "]")
                .param("home", event.scoreAfter().home()).param("away", event.scoreAfter().away())
                .param("points", event.points()).param("rawId", rawPayloadId)
                .param("now", java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                .update();
    }

    private static String durableRows(JdbcClient jdbc) {
        return jdbc.sql("""
                        SELECT (SELECT string_agg(concat_ws('|', event_id, sequence_number, revision,
                                    event_type, period, clock_millis_remaining, home_score, away_score,
                                    points, participant_ids::TEXT), ';' ORDER BY sequence_number)
                                FROM canonical_events)
                            || '#' || (SELECT concat_ws('|', status, period, last_sequence, home_score,
                                    away_score, player_points::TEXT, state_version)
                                FROM game_checkpoints)
                        """).query(String.class).single();
    }

    private static DataSource dataSource(String schema) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setCurrentSchema(schema);
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static DataSource baseDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
