package com.courtpulse.domain.alert;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameState;
import java.util.Optional;

public interface AlertRule {
    Optional<Alert> evaluate(GameState previousState, GameState nextState, CanonicalEvent event);
}
