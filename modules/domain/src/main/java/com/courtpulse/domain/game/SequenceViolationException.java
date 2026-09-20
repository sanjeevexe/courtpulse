package com.courtpulse.domain.game;

public final class SequenceViolationException extends IllegalArgumentException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public SequenceViolationException(long expected, long actual) {
        super("Expected event sequence " + expected + " but received " + actual);
    }
}
