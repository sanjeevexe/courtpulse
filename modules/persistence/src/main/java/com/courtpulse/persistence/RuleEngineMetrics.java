package com.courtpulse.persistence;

import com.courtpulse.domain.alert.RuleType;
import java.time.Duration;

public interface RuleEngineMetrics {
    RuleEngineMetrics NONE = new RuleEngineMetrics() {};

    default void lookup(Duration duration, int loaded, long skippedDisabled) {}
    default void evaluated(RuleType type, Duration duration) {}
    default void matched(RuleType type) {}
    default void alertCreated(RuleType type) {}
    default void duplicateSuppressed(RuleType type) {}
    default void quotaRejected() {}
}
