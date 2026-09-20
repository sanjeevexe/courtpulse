package com.courtpulse.domain.replay;

import com.courtpulse.domain.alert.Alert;
import com.courtpulse.domain.alert.AlertRule;
import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameReducer;
import com.courtpulse.domain.game.GameState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class GameEventProcessor {
    ProcessingOutcome process(
            GameState state,
            CanonicalEvent event,
            List<? extends AlertRule> rules,
            Set<String> emittedTriggerKeys) {
        if (state.appliedEventIdentities().contains(event.identity())) {
            return new ProcessingOutcome(state, List.of(), false);
        }

        GameState nextState = GameReducer.apply(state, event);
        List<Alert> alerts = new ArrayList<>();
        for (AlertRule rule : rules) {
            rule.evaluate(state, nextState, event).ifPresent(alert -> {
                if (emittedTriggerKeys.add(alert.triggerKey())) {
                    alerts.add(alert);
                }
            });
        }
        return new ProcessingOutcome(nextState, alerts, true);
    }
}
