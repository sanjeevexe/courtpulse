package com.courtpulse.persistence;

import com.courtpulse.domain.alert.Alert;
import com.courtpulse.domain.game.GameState;
import java.util.List;

public record DurableProcessingResult(
        boolean accepted,
        GameState state,
        List<Alert> newlyCreatedAlerts,
        String finalStateChecksum) {

    public DurableProcessingResult {
        newlyCreatedAlerts = List.copyOf(newlyCreatedAlerts);
    }
}
