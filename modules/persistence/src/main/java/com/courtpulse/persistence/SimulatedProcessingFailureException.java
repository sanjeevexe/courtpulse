package com.courtpulse.persistence;

public final class SimulatedProcessingFailureException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public SimulatedProcessingFailureException(String message) {
        super(message);
    }
}
