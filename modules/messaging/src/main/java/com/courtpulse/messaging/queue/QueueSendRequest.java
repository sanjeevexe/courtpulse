package com.courtpulse.messaging.queue;

import java.util.Objects;

public record QueueSendRequest(
        String body,
        String messageGroupId,
        String deduplicationId,
        String traceparent) {

    public QueueSendRequest {
        Objects.requireNonNull(body, "body is required");
        messageGroupId = requireText(messageGroupId, "messageGroupId");
        deduplicationId = requireText(deduplicationId, "deduplicationId");
        if (traceparent != null && com.courtpulse.observability.TraceContext.parse(traceparent) == null) {
            throw new IllegalArgumentException("traceparent must be a valid W3C context");
        }
    }

    public QueueSendRequest(String body, String messageGroupId, String deduplicationId) {
        this(body, messageGroupId, deduplicationId, null);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
