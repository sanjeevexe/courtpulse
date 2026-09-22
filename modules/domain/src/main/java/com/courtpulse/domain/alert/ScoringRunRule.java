package com.courtpulse.domain.alert;

import java.util.Map;
import java.util.Optional;

public record ScoringRunRule(
        String ruleId,
        String ownerSubject,
        String teamId,
        int threshold) implements AlertRule {
    public ScoringRunRule {
        requireText(ruleId, "ruleId");
        requireText(ownerSubject, "ownerSubject");
        requireText(teamId, "teamId");
        if (teamId.length() > 200) {
            throw new IllegalArgumentException("teamId must be at most 200 characters");
        }
        if (threshold < 1 || threshold > 100) {
            throw new IllegalArgumentException("threshold must be between 1 and 100");
        }
    }

    @Override
    public Optional<Alert> evaluate(RuleEvaluationFacts facts) {
        ScoringRunFacts run = facts.scoringRun();
        if (run == null || !teamId.equals(run.teamId())
                || run.previousPoints() >= threshold || run.nextPoints() < threshold) {
            return Optional.empty();
        }
        return Optional.of(new Alert(
                "SCORING_RUN:%s:%d".formatted(ruleId, run.runStartSequence()),
                ruleId,
                ownerSubject,
                ruleType(),
                facts.nextState().gameId(),
                facts.event().eventId(),
                "%s reached a %d-point unanswered run".formatted(teamId, threshold),
                Map.of(
                        "teamId", teamId,
                        "threshold", Integer.toString(threshold),
                        "verifiedRunPoints", Integer.toString(run.nextPoints()),
                        "runStartSequence", Long.toString(run.runStartSequence()))));
    }

    @Override
    public RuleType ruleType() {
        return RuleType.SCORING_RUN;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
