package com.courtpulse.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record GameSnapshotReadModel(
        String gameId,
        String source,
        String homeTeamId,
        String awayTeamId,
        String status,
        int period,
        long clockMillisRemaining,
        int homeScore,
        int awayScore,
        Map<String, Integer> playerPoints,
        long stateVersion,
        long lastAppliedSequence,
        String stateChecksum,
        List<RecentEventReadModel> recentEvents,
        Instant updatedAt,
        DataStatus dataStatus) {
    public GameSnapshotReadModel {
        playerPoints = Map.copyOf(playerPoints);
        recentEvents = List.copyOf(recentEvents);
    }
}
