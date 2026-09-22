package com.courtpulse.messaging.delivery;

public final class EmailSendException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final boolean retryable;

    public EmailSendException(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
