package com.courtpulse.query;

public final class GameNotFoundException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public GameNotFoundException(String gameId) {
        super("No game exists with ID " + gameId);
    }
}
