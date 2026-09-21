package com.courtpulse.query;

import java.time.Instant;

public record ProcessingHealthReadModel(
        long pending,
        long publishing,
        long retryScheduled,
        long sent,
        long failed,
        long deferredNotifications,
        Long oldestEligiblePendingAgeSeconds,
        long blockedGames,
        long processedEvents,
        long alerts,
        Instant apiTimestamp) {}
