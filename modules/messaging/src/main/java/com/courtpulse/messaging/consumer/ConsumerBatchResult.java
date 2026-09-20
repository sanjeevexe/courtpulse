package com.courtpulse.messaging.consumer;

public record ConsumerBatchResult(
        long received,
        long accepted,
        long suppressed,
        long deleted,
        long failed) {

    public static ConsumerBatchResult empty() {
        return new ConsumerBatchResult(0, 0, 0, 0, 0);
    }

    public ConsumerBatchResult plus(ConsumerBatchResult other) {
        return new ConsumerBatchResult(
                received + other.received,
                accepted + other.accepted,
                suppressed + other.suppressed,
                deleted + other.deleted,
                failed + other.failed);
    }
}
