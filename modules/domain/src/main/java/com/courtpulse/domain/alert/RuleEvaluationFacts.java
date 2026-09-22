package com.courtpulse.domain.alert;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameState;
import java.util.Objects;

public record RuleEvaluationFacts(
        GameState previousState,
        GameState nextState,
        CanonicalEvent event,
        ScoringRunFacts scoringRun) {
    public RuleEvaluationFacts {
        Objects.requireNonNull(previousState, "previousState is required");
        Objects.requireNonNull(nextState, "nextState is required");
        Objects.requireNonNull(event, "event is required");
    }
}
