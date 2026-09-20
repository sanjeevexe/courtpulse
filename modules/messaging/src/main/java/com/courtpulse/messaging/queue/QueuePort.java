package com.courtpulse.messaging.queue;

import java.time.Duration;
import java.util.List;

/** Broker-independent queue boundary. Infrastructure-specific clients stay behind this interface. */
public interface QueuePort extends AutoCloseable {
    String send(QueueSendRequest request);

    List<ReceivedQueueMessage> receive(int maxMessages, Duration waitTime);

    void delete(String receiptHandle);

    QueueDepth depth();

    @Override
    default void close() {}
}
