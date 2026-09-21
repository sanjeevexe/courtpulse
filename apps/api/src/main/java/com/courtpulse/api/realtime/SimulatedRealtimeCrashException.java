package com.courtpulse.api.realtime;

public final class SimulatedRealtimeCrashException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public SimulatedRealtimeCrashException(String message) {
        super(message);
    }
}
