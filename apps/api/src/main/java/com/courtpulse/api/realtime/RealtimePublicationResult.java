package com.courtpulse.api.realtime;

public record RealtimePublicationResult(
        int claimed,
        int completed,
        int retryScheduled,
        int failed,
        int lostLease) {
    public static RealtimePublicationResult empty() {
        return new RealtimePublicationResult(0, 0, 0, 0, 0);
    }

    public RealtimePublicationResult plus(RealtimePublicationResult other) {
        return new RealtimePublicationResult(
                claimed + other.claimed,
                completed + other.completed,
                retryScheduled + other.retryScheduled,
                failed + other.failed,
                lostLease + other.lostLease);
    }
}
