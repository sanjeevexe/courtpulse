package com.courtpulse.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcInspectionRepository {
    private final JdbcClient jdbc;

    public JdbcInspectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public DatabaseCounts counts() {
        return new DatabaseCounts(
                count("games"),
                count("raw_provider_payloads"),
                count("canonical_events"),
                count("game_checkpoints"),
                count("processed_events"),
                count("alert_instances"),
                count("outbox"),
                count("replay_runs"));
    }

    public long countOutboxByType(String eventType) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox WHERE event_type = :eventType")
                .param("eventType", eventType)
                .query(Long.class)
                .single();
    }

    public long checkpointVersion(String gameId) {
        return jdbc.sql("SELECT state_version FROM game_checkpoints WHERE game_id = :gameId")
                .param("gameId", gameId)
                .query(Long.class)
                .single();
    }

    private long count(String table) {
        String sql = switch (table) {
            case "games" -> "SELECT COUNT(*) FROM games";
            case "raw_provider_payloads" -> "SELECT COUNT(*) FROM raw_provider_payloads";
            case "canonical_events" -> "SELECT COUNT(*) FROM canonical_events";
            case "game_checkpoints" -> "SELECT COUNT(*) FROM game_checkpoints";
            case "processed_events" -> "SELECT COUNT(*) FROM processed_events";
            case "alert_instances" -> "SELECT COUNT(*) FROM alert_instances";
            case "outbox" -> "SELECT COUNT(*) FROM outbox";
            case "replay_runs" -> "SELECT COUNT(*) FROM replay_runs";
            default -> throw new IllegalArgumentException("Unsupported table: " + table);
        };
        return jdbc.sql(sql).query(Long.class).single();
    }
}
