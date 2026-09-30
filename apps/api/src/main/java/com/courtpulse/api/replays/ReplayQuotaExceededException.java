package com.courtpulse.api.replays;

public final class ReplayQuotaExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ReplayQuotaExceededException(String safeMessage) {
        super(safeMessage);
    }
}
