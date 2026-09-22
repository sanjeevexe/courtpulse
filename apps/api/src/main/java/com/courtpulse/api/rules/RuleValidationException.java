package com.courtpulse.api.rules;

public final class RuleValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public RuleValidationException(String message) {
        super(message);
    }
}
