package com.courtpulse.persistence;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptRecord(
        UUID id,
        int attemptNumber,
        String outcome,
        String errorCode,
        Instant completedAt) {}
