package com.courtpulse.messaging.queue;

public record ReceivedQueueMessage(
        String providerMessageId,
        String receiptHandle,
        String body,
        int receiveCount) {}
