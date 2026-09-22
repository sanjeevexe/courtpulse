package com.courtpulse.persistence;

import com.courtpulse.domain.event.CanonicalEvent;

public interface AlertRuleSource {
    AlertRuleBatch relevantRules(CanonicalEvent event);
}
