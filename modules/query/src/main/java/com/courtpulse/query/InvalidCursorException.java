package com.courtpulse.query;

public final class InvalidCursorException extends IllegalArgumentException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public InvalidCursorException(String message) {
        super(message);
    }

    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
