package com.courtpulse.messaging.publisher;

public final class SimulatedPublisherCrashException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public SimulatedPublisherCrashException(String message) {
        super(message);
    }
}
