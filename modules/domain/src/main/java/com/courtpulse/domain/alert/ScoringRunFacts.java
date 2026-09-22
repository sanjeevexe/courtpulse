package com.courtpulse.domain.alert;

public record ScoringRunFacts(
        String teamId,
        int previousPoints,
        int nextPoints,
        long runStartSequence) {
    public ScoringRunFacts {
        if (teamId == null || teamId.isBlank()) {
            throw new IllegalArgumentException("teamId is required");
        }
        if (previousPoints < 0 || nextPoints < 0 || runStartSequence < 1) {
            throw new IllegalArgumentException("scoring-run facts must be non-negative with a positive start");
        }
        if (teamId.length() > 200) {
            throw new IllegalArgumentException("scoring-run facts exceed their supported bounds");
        }
    }
}
