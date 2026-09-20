package com.courtpulse.persistence;

import com.courtpulse.domain.game.GameState;
import java.util.List;

public record DurableReplayResult(
        ImportResult importResult,
        GameState finalState,
        List<StoredAlert> alerts,
        long acceptedEventCount,
        long suppressedDuplicateCount,
        String finalStateChecksum,
        DatabaseCounts databaseCounts) {

    public DurableReplayResult {
        alerts = List.copyOf(alerts);
    }
}
