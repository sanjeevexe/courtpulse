package com.courtpulse.api.replays;

public final class ReplayConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ReplayConflictException(String safeMessage) {
        super(safeMessage);
    }
}
