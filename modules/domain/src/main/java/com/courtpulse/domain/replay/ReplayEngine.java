package com.courtpulse.domain.replay;

import com.courtpulse.domain.alert.Alert;
import com.courtpulse.domain.alert.AlertRule;
import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic, in-memory replay orchestration for the first milestone. */
public final class ReplayEngine {
    private final GameEventProcessor processor = new GameEventProcessor();

    public ReplayResult replay(
            String gameId, List<CanonicalEvent> events, List<? extends AlertRule> rules) {
        Objects.requireNonNull(events, "events are required");
        Objects.requireNonNull(rules, "rules are required");

        GameState state = GameState.initial(gameId);
        List<Alert> alerts = new ArrayList<>();
        Set<String> emittedTriggerKeys = new LinkedHashSet<>();
        long accepted = 0;
        long suppressed = 0;

        for (CanonicalEvent event : events) {
            ProcessingOutcome outcome = processor.process(state, event, rules, emittedTriggerKeys);
            state = outcome.state();
            alerts.addAll(outcome.alerts());
            if (outcome.accepted()) {
                accepted++;
            } else {
                suppressed++;
            }
        }

        return new ReplayResult(
                state,
                alerts,
                accepted,
                suppressed,
                StateChecksum.sha256(state));
    }
}
