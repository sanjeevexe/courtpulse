package com.courtpulse.api.rules;

public final class RuleConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public RuleConflictException(String message) {
        super(message);
    }
}
