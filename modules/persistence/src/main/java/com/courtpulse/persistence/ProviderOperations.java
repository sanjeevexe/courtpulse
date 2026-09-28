package com.courtpulse.persistence;

import java.util.List;

/** Aggregate provider health for operators: fixed codes and counts, never payloads or keys. */
public record ProviderOperations(List<Source> providers) {
    public ProviderOperations {
        providers = List.copyOf(providers);
    }

    public record Source(
            String source,
            String circuitState,
            long requestsTotal,
            long rateLimitedTotal,
            long failuresTotal,
            int consecutiveFailures,
            String lastErrorCode,
            Long lastSuccessAgeSeconds,
            long scheduledGames,
            long liveGames,
            long finalGames,
            Long stalestLiveFeedAgeSeconds,
            long dataIncidents) {}
}
