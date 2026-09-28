package com.courtpulse.providers.live;

import java.time.Instant;
import java.util.Objects;

public record ProviderGame(
        String source,
        String providerGameId,
        String gameId,
        ProviderTeam homeTeam,
        ProviderTeam awayTeam,
        Instant scheduledAt,
        String providerStatus,
        ProviderLifecycle lifecycle) {
    public ProviderGame {
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(providerGameId, "providerGameId is required");
        Objects.requireNonNull(gameId, "gameId is required");
        Objects.requireNonNull(homeTeam, "homeTeam is required");
        Objects.requireNonNull(awayTeam, "awayTeam is required");
        Objects.requireNonNull(providerStatus, "providerStatus is required");
        Objects.requireNonNull(lifecycle, "lifecycle is required");
        if (homeTeam.teamId().equals(awayTeam.teamId())) {
            throw new IllegalArgumentException("Home and away teams must differ");
        }
    }
}
