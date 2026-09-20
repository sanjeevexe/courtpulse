package com.courtpulse.providers.fixture;

public final class FixtureFormatException extends IllegalArgumentException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public FixtureFormatException(String message) {
        super(message);
    }

    public FixtureFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
