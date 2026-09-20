package com.courtpulse.domain.replay;

import com.courtpulse.domain.alert.Alert;
import com.courtpulse.domain.game.GameState;
import java.util.List;

record ProcessingOutcome(GameState state, List<Alert> alerts, boolean accepted) {
    ProcessingOutcome {
        alerts = List.copyOf(alerts);
    }
}
