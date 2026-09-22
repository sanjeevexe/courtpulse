package com.courtpulse.api.rules;

public final class RuleQuotaExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public RuleQuotaExceededException(int limit) {
        super("The account alert-rule limit of " + limit + " has been reached.");
    }

    public RuleQuotaExceededException(String safeMessage) {
        super(safeMessage);
    }
}
