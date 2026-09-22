package com.courtpulse.persistence;

/** Sanitized aggregate rule-engine state; contains no owner or rule identifiers. */
public record RuleOperationalSummary(
        long ownedRules,
        long enabledOwnedRules,
        long disabledOwnedRules,
        long systemRules,
        long privateAlerts) {}
