package com.courtpulse.messaging.queue;

/** Result of one message in a batch send: exactly one of the two fields is set. */
public record SendOutcome(String providerMessageId, QueuePublishException failure) {
    public static SendOutcome sent(String providerMessageId) {
        return new SendOutcome(providerMessageId, null);
    }

    public static SendOutcome failed(QueuePublishException failure) {
        return new SendOutcome(null, failure);
    }

    public boolean succeeded() {
        return failure == null;
    }
}
