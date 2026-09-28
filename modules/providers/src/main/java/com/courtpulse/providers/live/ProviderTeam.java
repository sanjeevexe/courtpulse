package com.courtpulse.providers.live;

import java.util.Objects;

/** A team as CourtPulse identifies it, with optional provider display labels. */
public record ProviderTeam(String teamId, String providerTeamId, String name, String abbreviation) {
    public ProviderTeam {
        Objects.requireNonNull(teamId, "teamId is required");
        Objects.requireNonNull(providerTeamId, "providerTeamId is required");
    }
}
