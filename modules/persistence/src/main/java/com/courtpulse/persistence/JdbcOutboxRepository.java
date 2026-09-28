package com.courtpulse.persistence;

import com.courtpulse.observability.TraceContext;
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
        String destination = "CANONICAL_EVENT_READY".equals(eventType)
                ? OutboxDestination.GAME_EVENTS.name()
                : OutboxDestination.FUTURE_NOTIFICATIONS.name();
        Object messageGroupId = payload.get("gameId");
        int rows = jdbc.sql("""
                        INSERT INTO outbox (
                            id, deduplication_key, aggregate_type, aggregate_id, event_type,
                            payload, destination, message_group_id, status, attempts,
                            next_attempt_at, created_at, traceparent)
                        VALUES (
                            :id, :deduplicationKey, :aggregateType, :aggregateId, :eventType,
                            CAST(:payload AS JSONB), :destination, :messageGroupId,
                            'PENDING', 0, :now, :now, :traceparent)
                        ON CONFLICT (deduplication_key) DO NOTHING
                        """)
                .params(parameters(
                        deduplicationKey,
                        aggregateType,
                        aggregateId,
                        eventType,
                        payload,
                        destination,
                        messageGroupId,
                        now))
                .update();
        return rows == 1;
    }

    private Map<String, Object> parameters(
            String deduplicationKey,
            String aggregateType,
            String aggregateId,
            String eventType,
            Map<String, ?> payload,
            String destination,
            Object messageGroupId,
            Instant now) {
        java.util.HashMap<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("id", UUID.randomUUID());
        parameters.put("deduplicationKey", deduplicationKey);
        parameters.put("aggregateType", aggregateType);
        parameters.put("aggregateId", aggregateId);
        parameters.put("eventType", eventType);
        parameters.put("payload", json.write(payload));
        parameters.put("destination", destination);
        parameters.put("messageGroupId", messageGroupId);
        parameters.put("now", SqlTime.offset(now));
        parameters.put("traceparent", TraceContext.currentTraceparent());
        return parameters;
    }
}
