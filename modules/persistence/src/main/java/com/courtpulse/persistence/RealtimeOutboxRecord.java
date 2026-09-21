package com.courtpulse.persistence;

import java.time.Instant;
import java.util.UUID;

public record RealtimeOutboxRecord(
        UUID outboxId,
        String eventType,
        String gameId,
        long stateVersion,
        String eventId,
        String triggerKey,
        int attempt,
        Instant createdAt) {}
