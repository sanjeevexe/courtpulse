package com.courtpulse.api.realtime;

public final class RealtimeProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public RealtimeProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
