package com.courtpulse.messaging.consumer;

public final class SimulatedConsumerCrashException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public SimulatedConsumerCrashException(String message) {
        super(message);
    }
}
