package com.courtpulse.messaging.queue;

public final class QueuePublishException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public QueuePublishException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public QueuePublishException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
