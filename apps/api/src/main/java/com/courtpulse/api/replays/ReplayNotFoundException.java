package com.courtpulse.api.replays;

public final class ReplayNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ReplayNotFoundException(String safeMessage) {
        super(safeMessage);
    }
}
