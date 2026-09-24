package com.courtpulse.durablereplay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DurableReplayApplicationTest {
    @Test
    void commandRunsThenRestartsAgainstTheSamePostgresDatabase() throws Exception {
        EmbeddedPostgres postgres = null;
        PostgreSQLContainer dockerPostgres = null;
        try {
            String url;
            String username;
            String password;
            try {
                postgres = EmbeddedPostgres.builder().start();
                url = "jdbc:postgresql://localhost:%d/postgres".formatted(postgres.getPort());
                username = "postgres";
                password = "postgres";
            } catch (IllegalStateException embeddedUnavailable) {
                dockerPostgres = new PostgreSQLContainer("postgres:17.6-alpine");
                dockerPostgres.start();
                url = dockerPostgres.getJdbcUrl();
                username = dockerPostgres.getUsername();
                password = dockerPostgres.getPassword();
            }
            Map<String, Object> databaseProperties = Map.of(
                    "spring.datasource.url", url,
                    "spring.datasource.username", username,
                    "spring.datasource.password", password,
                    "logging.level.root",
                    "ERROR");

            runApplication(databaseProperties, "--reset");
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setURL((String) databaseProperties.get("spring.datasource.url"));
            dataSource.setUser(username);
            dataSource.setPassword(password);
            JdbcClient jdbc = JdbcClient.create(dataSource);

            assertEquals(20L, count(jdbc, "canonical_events"));
            assertEquals(20L, count(jdbc, "processed_events"));
            assertEquals(1L, count(jdbc, "alert_instances"));
            assertEquals(41L, count(jdbc, "outbox"));
            assertEquals(1L, count(jdbc, "replay_runs"));

            runApplication(databaseProperties, "--reprocess-existing");
            assertEquals(20L, count(jdbc, "processed_events"));
            assertEquals(1L, count(jdbc, "alert_instances"));
            assertEquals(41L, count(jdbc, "outbox"));
            assertEquals(2L, count(jdbc, "replay_runs"));
            assertEquals(
                    "06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca",
                    jdbc.sql("SELECT state_checksum FROM game_checkpoints WHERE game_id = 'game_synthetic_001'")
                            .query(String.class)
                            .single());
            assertEquals(0L, jdbc.sql("SELECT accepted_events FROM replay_runs WHERE accepted_events = 0")
                    .query(Long.class)
                    .single());
            assertEquals(20L, jdbc.sql("SELECT suppressed_duplicates FROM replay_runs WHERE accepted_events = 0")
                    .query(Long.class)
                    .single());

            runApplication(databaseProperties, "--reset", "--inject-duplicates");
            assertEquals(20L, count(jdbc, "processed_events"));
            assertEquals(1L, count(jdbc, "alert_instances"));
            assertEquals(41L, count(jdbc, "outbox"));
            assertEquals(20L, jdbc.sql("SELECT accepted_events FROM replay_runs")
                    .query(Long.class)
                    .single());
            assertEquals(2L, jdbc.sql("SELECT suppressed_duplicates FROM replay_runs")
                    .query(Long.class)
                    .single());
            assertEquals(
                    "06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca",
                    jdbc.sql("SELECT final_state_checksum FROM replay_runs")
                            .query(String.class)
                            .single());
        } finally {
            if (postgres != null) postgres.close();
            if (dockerPostgres != null) dockerPostgres.stop();
        }
    }

    private static void runApplication(Map<String, Object> properties, String... args) {
        SpringApplication application = new SpringApplication(DurableReplayApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        List<String> commandLine = new ArrayList<>(Arrays.asList(args));
        properties.forEach((key, value) -> commandLine.add("--%s=%s".formatted(key, value)));
        ConfigurableApplicationContext context = application.run(commandLine.toArray(String[]::new));
        context.close(); // Simulate the process ending before the next invocation.
    }

    private static long count(JdbcClient jdbc, String table) {
        String sql = switch (table) {
            case "canonical_events" -> "SELECT COUNT(*) FROM canonical_events";
            case "processed_events" -> "SELECT COUNT(*) FROM processed_events";
            case "alert_instances" -> "SELECT COUNT(*) FROM alert_instances";
            case "outbox" -> "SELECT COUNT(*) FROM outbox";
            case "replay_runs" -> "SELECT COUNT(*) FROM replay_runs";
            default -> throw new IllegalArgumentException("Unsupported table: " + table);
        };
        return jdbc.sql(sql).query(Long.class).single();
    }
}
