package com.courtpulse.query;

import java.time.Instant;

public record GameSummaryReadModel(
        String gameId,
        String source,
        String homeTeamId,
        String awayTeamId,
        String status,
        long stateVersion,
        int homeScore,
        int awayScore,
        int period,
        long clockMillisRemaining,
        long lastAppliedSequence,
        Instant updatedAt,
        String stateChecksum,
        DataStatus dataStatus) {}
