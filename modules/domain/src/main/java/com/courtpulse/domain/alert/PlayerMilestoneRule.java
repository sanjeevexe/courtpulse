package com.courtpulse.domain.alert;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameState;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record PlayerMilestoneRule(String ruleId, String playerId, int threshold) implements AlertRule {
    public PlayerMilestoneRule {
        ruleId = requireText(ruleId, "ruleId");
        playerId = requireText(playerId, "playerId");
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be positive");
        }
    }

    @Override
    public Optional<Alert> evaluate(
            GameState previousState, GameState nextState, CanonicalEvent event) {
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
                nextState.gameId(),
                event.eventId(),
                "%s reached %d points".formatted(playerId, threshold),
                Map.of(
                        "playerId", playerId,
                        "stat", "POINTS",
                        "threshold", Integer.toString(threshold),
                        "verifiedTotal", Integer.toString(nextPoints))));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
