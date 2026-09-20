package com.courtpulse.domain.replay;

import com.courtpulse.domain.alert.Alert;
import com.courtpulse.domain.game.GameState;
import java.util.List;

public record ReplayResult(
        GameState finalState,
        List<Alert> alerts,
        long acceptedEventCount,
        long suppressedDuplicateCount,
        String finalStateChecksum) {

    public ReplayResult {
        alerts = List.copyOf(alerts);
    }
}
