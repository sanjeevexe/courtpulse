package com.courtpulse.domain.replay;

import com.courtpulse.domain.alert.Alert;
import com.courtpulse.domain.alert.AlertRule;
import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventFingerprint;
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
        GameReducer.validateEventMetadata(state, event);
        String acceptedFingerprint = state.appliedEventFingerprints().get(event.identity());
        if (acceptedFingerprint != null) {
            if (!acceptedFingerprint.equals(EventFingerprint.sha256(event))) {
                throw new IllegalArgumentException(
                        "Conflicting duplicate payload for identity " + event.identity());
            }
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
