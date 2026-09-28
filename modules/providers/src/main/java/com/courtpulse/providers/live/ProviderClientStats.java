package com.courtpulse.providers.live;

import java.time.Instant;

/** Counters since the previous drain plus the client's current circuit view. */
public record ProviderClientStats(
        long requests,
        long rateLimited,
        long failures,
        String circuitState,
        String lastErrorCode,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        int consecutiveFailures) {}
