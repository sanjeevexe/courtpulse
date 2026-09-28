package com.courtpulse.query;

/** Optional provider display metadata; null values mean only stable IDs are known. */
public record TeamLabels(
        String homeTeamName,
        String homeTeamAbbreviation,
        String awayTeamName,
        String awayTeamAbbreviation) {
    public static final TeamLabels UNKNOWN = new TeamLabels(null, null, null, null);
}
