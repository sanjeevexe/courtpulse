package com.courtpulse.persistence;

import java.time.Instant;
import java.util.UUID;

public record DeliveryHistoryRecord(
        UUID id,
        UUID alertId,
        String channel,
        String status,
        int attempts,
        Instant createdAt,
        Instant deliveredAt,
        Instant nextAttemptAt,
        String lastErrorCode) {}
