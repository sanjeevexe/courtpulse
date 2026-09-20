package com.courtpulse.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcOutboxRepository {
    private final JdbcClient jdbc;
    private final PersistenceJson json;

    public JdbcOutboxRepository(JdbcClient jdbc, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.json = new PersistenceJson(objectMapper);
    }

    public boolean insert(
            String deduplicationKey,
            String aggregateType,
            String aggregateId,
            String eventType,
            Map<String, ?> payload,
            Instant now) {
        int rows = jdbc.sql("""
                        INSERT INTO outbox (
                            id, deduplication_key, aggregate_type, aggregate_id, event_type,
                            payload, status, attempts, next_attempt_at, created_at)
                        VALUES (
                            :id, :deduplicationKey, :aggregateType, :aggregateId, :eventType,
                            CAST(:payload AS JSONB), 'PENDING', 0, :now, :now)
                        ON CONFLICT (deduplication_key) DO NOTHING
                        """)
                .params(Map.of(
                        "id", UUID.randomUUID(),
                        "deduplicationKey", deduplicationKey,
                        "aggregateType", aggregateType,
                        "aggregateId", aggregateId,
                        "eventType", eventType,
                        "payload", json.write(payload),
                        "now", SqlTime.offset(now)))
                .update();
        return rows == 1;
    }
}
