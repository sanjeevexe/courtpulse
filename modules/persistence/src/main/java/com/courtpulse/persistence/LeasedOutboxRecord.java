package com.courtpulse.persistence;

import java.time.Instant;
import java.util.UUID;

public record LeasedOutboxRecord(
        UUID outboxId,
        String deduplicationKey,
        String eventId,
        String gameId,
        long sequence,
        String source,
        String providerEventId,
        int revision,
        Instant occurredAt,
        String messageGroupId,
        int attempt,
        String traceparent) {
    public LeasedOutboxRecord(UUID outboxId, String deduplicationKey, String eventId,
            String gameId, long sequence, String source, String providerEventId, int revision,
            Instant occurredAt, String messageGroupId, int attempt) {
        this(outboxId, deduplicationKey, eventId, gameId, sequence, source, providerEventId,
                revision, occurredAt, messageGroupId, attempt, null);
    }
}
