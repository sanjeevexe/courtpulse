package com.courtpulse.messaging.queue;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record GameEventEnvelope(
        String messageId,
        String messageType,
        int schemaVersion,
        String eventId,
        String gameId,
        long sequence,
        String source,
        String providerEventId,
        int revision,
        Instant occurredAt,
        UUID outboxId,
        String deduplicationKey,
        String correlationId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String MESSAGE_TYPE = "CANONICAL_EVENT_READY";

    public GameEventEnvelope {
        messageId = requireText(messageId, "messageId");
        messageType = requireText(messageType, "messageType");
        eventId = requireText(eventId, "eventId");
        gameId = requireText(gameId, "gameId");
        source = requireText(source, "source");
        providerEventId = requireText(providerEventId, "providerEventId");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(outboxId, "outboxId is required");
        deduplicationKey = requireText(deduplicationKey, "deduplicationKey");
        if (correlationId != null && correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must be null or non-blank");
        }
        if (!MESSAGE_TYPE.equals(messageType)) {
            throw new IllegalArgumentException("Unsupported messageType: " + messageType);
        }
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported queue schemaVersion: " + schemaVersion);
        }
        if (sequence < 1 || revision < 1) {
            throw new IllegalArgumentException("sequence and revision must be positive");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
