package com.courtpulse.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcReplayRunRepository {
    private final JdbcClient jdbc;

    public JdbcReplayRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID start(String fixtureName, String mode, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO replay_runs (id, fixture_name, mode, status, started_at)
                        VALUES (:id, :fixtureName, :mode, 'RUNNING', :now)
                        """)
                .params(Map.of(
                        "id", id,
                        "fixtureName", fixtureName,
                        "mode", mode,
                        "now", SqlTime.offset(now)))
                .update();
        return id;
    }

    public void complete(
            UUID id, long accepted, long suppressed, String checksum, Instant now) {
        jdbc.sql("""
                        UPDATE replay_runs
                        SET status = 'COMPLETED', accepted_events = :accepted,
                            suppressed_duplicates = :suppressed,
                            final_state_checksum = :checksum, completed_at = :now
                        WHERE id = :id
                        """)
                .params(Map.of(
                        "id", id,
                        "accepted", accepted,
                        "suppressed", suppressed,
                        "checksum", checksum,
                        "now", SqlTime.offset(now)))
                .update();
    }

    public void fail(UUID id, Instant now) {
        jdbc.sql("""
                        UPDATE replay_runs
                        SET status = 'FAILED', completed_at = :now
                        WHERE id = :id
                        """)
                .params(Map.of("id", id, "now", SqlTime.offset(now)))
                .update();
    }
}
