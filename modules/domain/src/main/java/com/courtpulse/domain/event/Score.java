package com.courtpulse.domain.event;

public record Score(int home, int away) {
    public Score {
        if (home < 0 || away < 0) {
            throw new IllegalArgumentException("Score values must be non-negative");
        }
    }
}
