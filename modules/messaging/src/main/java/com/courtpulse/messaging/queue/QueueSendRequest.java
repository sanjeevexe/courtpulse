package com.courtpulse.messaging.queue;

import java.util.Objects;

public record QueueSendRequest(
        String body,
        String messageGroupId,
        String deduplicationId) {

    public QueueSendRequest {
        Objects.requireNonNull(body, "body is required");
        messageGroupId = requireText(messageGroupId, "messageGroupId");
        deduplicationId = requireText(deduplicationId, "deduplicationId");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
