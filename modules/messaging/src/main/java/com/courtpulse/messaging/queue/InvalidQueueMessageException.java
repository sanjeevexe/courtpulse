package com.courtpulse.messaging.queue;

public final class InvalidQueueMessageException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public InvalidQueueMessageException(String message) {
        super(message);
    }

    public InvalidQueueMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
