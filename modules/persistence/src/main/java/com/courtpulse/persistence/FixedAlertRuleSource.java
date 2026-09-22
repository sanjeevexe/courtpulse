package com.courtpulse.persistence;

import com.courtpulse.domain.alert.AlertRule;
import com.courtpulse.domain.event.CanonicalEvent;
import java.util.List;

public final class FixedAlertRuleSource implements AlertRuleSource {
    private final List<AlertRule> rules;

    public FixedAlertRuleSource(List<? extends AlertRule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public AlertRuleBatch relevantRules(CanonicalEvent event) {
        return new AlertRuleBatch(rules, 0);
    }
}
