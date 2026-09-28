package com.courtpulse.messaging.queue;

import java.time.Duration;
import java.util.List;

/** Broker-independent queue boundary. Infrastructure-specific clients stay behind this interface. */
public interface QueuePort extends AutoCloseable {
    String send(QueueSendRequest request);

    /**
     * Sends up to ten messages in one request where the broker supports it. Outcomes are returned
     * in request order; each carries either a provider message ID or its own failure. Callers must
     * not put two messages of one FIFO group in a batch unless their order is guaranteed.
     */
    default List<SendOutcome> sendBatch(List<QueueSendRequest> requests) {
        return requests.stream().map(request -> {
            try {
                return SendOutcome.sent(send(request));
            } catch (QueuePublishException exception) {
                return SendOutcome.failed(exception);
            }
        }).toList();
    }

    List<ReceivedQueueMessage> receive(int maxMessages, Duration waitTime);

    void delete(String receiptHandle);

    QueueDepth depth();

    @Override
    default void close() {}
}
