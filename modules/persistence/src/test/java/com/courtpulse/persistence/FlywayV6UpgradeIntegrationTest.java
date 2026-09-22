package com.courtpulse.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class FlywayV6UpgradeIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.6-alpine");

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
