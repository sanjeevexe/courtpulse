package com.courtpulse.api.rules;

public final class RuleNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public RuleNotFoundException() {
        super("The requested alert rule was not found.");
    }
}
