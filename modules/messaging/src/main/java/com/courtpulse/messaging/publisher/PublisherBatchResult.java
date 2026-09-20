package com.courtpulse.messaging.publisher;

public record PublisherBatchResult(
        long claimed,
        long sent,
        long retryScheduled,
        long failed,
        long lostLease) {

    public static PublisherBatchResult empty() {
        return new PublisherBatchResult(0, 0, 0, 0, 0);
    }

    public PublisherBatchResult plus(PublisherBatchResult other) {
        return new PublisherBatchResult(
                claimed + other.claimed,
                sent + other.sent,
                retryScheduled + other.retryScheduled,
                failed + other.failed,
                lostLease + other.lostLease);
    }
}
