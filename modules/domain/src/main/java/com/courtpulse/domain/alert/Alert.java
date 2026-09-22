package com.courtpulse.domain.alert;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record Alert(
        String triggerKey,
        String ruleId,
        String ownerSubject,
        RuleType ruleType,
        String gameId,
        String triggeringEventId,
        String title,
        Map<String, String> context) {

    public Alert {
        Objects.requireNonNull(triggerKey, "triggerKey is required");
        Objects.requireNonNull(ruleId, "ruleId is required");
        Objects.requireNonNull(ruleType, "ruleType is required");
        Objects.requireNonNull(gameId, "gameId is required");
        Objects.requireNonNull(triggeringEventId, "triggeringEventId is required");
        Objects.requireNonNull(title, "title is required");
        Objects.requireNonNull(context, "context is required");
        if (ownerSubject != null && (ownerSubject.isBlank() || ownerSubject.length() > 255)) {
            throw new IllegalArgumentException("ownerSubject must contain at most 255 characters");
        }
        context = Map.copyOf(new TreeMap<>(context));
    }

    public Alert(
            String triggerKey,
            String ruleId,
            String gameId,
            String triggeringEventId,
            String title,
            Map<String, String> context) {
        this(triggerKey, ruleId, null, RuleType.PLAYER_POINTS, gameId,
                triggeringEventId, title, context);
    }
}
