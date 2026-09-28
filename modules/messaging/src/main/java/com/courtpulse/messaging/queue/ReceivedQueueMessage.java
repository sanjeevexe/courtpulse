package com.courtpulse.messaging.queue;

public record ReceivedQueueMessage(
        String providerMessageId,
        String receiptHandle,
        String body,
        int receiveCount,
        String traceparent) {
    public ReceivedQueueMessage(String providerMessageId, String receiptHandle,
            String body, int receiveCount) {
        this(providerMessageId, receiptHandle, body, receiveCount, null);
    }
}
