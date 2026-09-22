package com.courtpulse.domain.alert;

import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.game.GameStatus;
import java.util.Map;
import java.util.Optional;

public record CloseGameRule(
        String ruleId,
        String ownerSubject,
        int maximumMargin,
        int eligiblePeriod,
        long maximumClockMillisRemaining) implements AlertRule {
    public CloseGameRule {
        requireText(ruleId, "ruleId");
        requireText(ownerSubject, "ownerSubject");
        if (maximumMargin < 1 || maximumMargin > 20) {
            throw new IllegalArgumentException("maximumMargin must be between 1 and 20");
        }
        if (eligiblePeriod < 1 || eligiblePeriod > 4) {
            throw new IllegalArgumentException("eligiblePeriod must be between 1 and 4");
        }
        if (maximumClockMillisRemaining < 0 || maximumClockMillisRemaining > 720_000) {
            throw new IllegalArgumentException("maximumClockMillisRemaining must be between 0 and 720000");
        }
    }

    @Override
    public Optional<Alert> evaluate(RuleEvaluationFacts facts) {
        if (qualifies(facts.previousState()) || !qualifies(facts.nextState())) {
            return Optional.empty();
        }
        GameState next = facts.nextState();
        int margin = Math.abs(next.homeScore() - next.awayScore());
        return Optional.of(new Alert(
                "CLOSE_GAME:%s:%s".formatted(ruleId, facts.event().eventId()),
                ruleId,
                ownerSubject,
                ruleType(),
                next.gameId(),
                facts.event().eventId(),
                "Game entered a close-game window",
                Map.of(
                        "maximumMargin", Integer.toString(maximumMargin),
                        "verifiedMargin", Integer.toString(margin),
                        "period", Integer.toString(next.period()),
                        "clockMillisRemaining", Long.toString(next.clockMillisRemaining()))));
    }

    private boolean qualifies(GameState state) {
        return state.status() == GameStatus.LIVE
                && state.period() == eligiblePeriod
                && state.clockMillisRemaining() <= maximumClockMillisRemaining
                && Math.abs(state.homeScore() - state.awayScore()) <= maximumMargin;
    }

    @Override
    public RuleType ruleType() {
        return RuleType.CLOSE_GAME;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
