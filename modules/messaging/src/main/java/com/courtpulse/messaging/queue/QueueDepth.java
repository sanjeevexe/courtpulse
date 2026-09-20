package com.courtpulse.messaging.queue;

public record QueueDepth(long visible, long inFlight, long delayed) {
    public long total() {
        return visible + inFlight + delayed;
    }
}
