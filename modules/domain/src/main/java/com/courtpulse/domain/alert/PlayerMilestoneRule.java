package com.courtpulse.domain.alert;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameState;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record PlayerMilestoneRule(
        String ruleId, String ownerSubject, String playerId, int threshold) implements AlertRule {
    public PlayerMilestoneRule(String ruleId, String playerId, int threshold) {
        this(ruleId, null, playerId, threshold);
    }

    public PlayerMilestoneRule {
        ruleId = requireText(ruleId, "ruleId");
        playerId = requireText(playerId, "playerId");
        if (ownerSubject != null && (ownerSubject.isBlank() || ownerSubject.length() > 255)) {
            throw new IllegalArgumentException("ownerSubject must contain at most 255 characters");
        }
        if (playerId.length() > 200) {
            throw new IllegalArgumentException("playerId must be at most 200 characters");
        }
        if (threshold < 1 || threshold > 200) {
            throw new IllegalArgumentException("threshold must be between 1 and 200");
        }
    }

    @Override
    public Optional<Alert> evaluate(RuleEvaluationFacts facts) {
        GameState previousState = facts.previousState();
        GameState nextState = facts.nextState();
        CanonicalEvent event = facts.event();
        int previousPoints = previousState.pointsFor(playerId);
        int nextPoints = nextState.pointsFor(playerId);
        if (previousPoints >= threshold || nextPoints < threshold) {
            return Optional.empty();
        }

        String triggerKey = "PLAYER_MILESTONE:%s:%s:POINTS:%d"
                .formatted(ruleId, nextState.gameId(), threshold);
        return Optional.of(new Alert(
                triggerKey,
                ruleId,
                ownerSubject,
                ruleType(),
                nextState.gameId(),
                event.eventId(),
                "%s reached %d points".formatted(playerId, threshold),
                Map.of(
                        "playerId", playerId,
                        "stat", "POINTS",
                        "threshold", Integer.toString(threshold),
                        "verifiedTotal", Integer.toString(nextPoints))));
    }

    @Override
    public RuleType ruleType() {
        return RuleType.PLAYER_POINTS;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " must not be blank or padded");
        }
        return value;
    }
}
