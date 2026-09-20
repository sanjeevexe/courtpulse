package com.courtpulse.domain.alert;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record Alert(
        String triggerKey,
        String ruleId,
        String gameId,
        String triggeringEventId,
        String title,
        Map<String, String> context) {

    public Alert {
        Objects.requireNonNull(triggerKey, "triggerKey is required");
        Objects.requireNonNull(ruleId, "ruleId is required");
        Objects.requireNonNull(gameId, "gameId is required");
        Objects.requireNonNull(triggeringEventId, "triggeringEventId is required");
        Objects.requireNonNull(title, "title is required");
        context = Map.copyOf(new TreeMap<>(context));
    }
}
