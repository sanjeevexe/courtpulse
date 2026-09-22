package com.courtpulse.persistence;

import com.courtpulse.domain.alert.AlertRule;
import java.util.List;

public record AlertRuleBatch(List<AlertRule> enabledRules, long disabledCount) {
    public AlertRuleBatch {
        enabledRules = List.copyOf(enabledRules);
        if (disabledCount < 0) {
            throw new IllegalArgumentException("disabledCount must be non-negative");
        }
    }
}
