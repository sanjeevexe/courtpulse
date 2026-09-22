package com.courtpulse.persistence;

import com.courtpulse.domain.alert.RuleType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/** Bounded-cardinality rule metrics. Domain identifiers are deliberately never tags. */
public final class MicrometerRuleEngineMetrics implements RuleEngineMetrics {
    private final MeterRegistry registry;

    public MicrometerRuleEngineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void lookup(Duration duration, int loaded, long skippedDisabled) {
        registry.counter("courtpulse.rules.lookup").increment();
        registry.counter("courtpulse.rules.loaded").increment(loaded);
        registry.counter("courtpulse.rules.skipped", "reason", "disabled")
                .increment(skippedDisabled);
        Timer.builder("courtpulse.rules.lookup.duration").register(registry).record(duration);
    }

    @Override
    public void evaluated(RuleType type, Duration duration) {
        String ruleType = type.name();
        registry.counter("courtpulse.rules.evaluated", "type", ruleType).increment();
        Timer.builder("courtpulse.rules.evaluation.duration")
                .tag("type", ruleType)
                .register(registry)
                .record(duration);
    }

    @Override
    public void matched(RuleType type) {
        registry.counter("courtpulse.rules.matched", "type", type.name()).increment();
    }

    @Override
    public void alertCreated(RuleType type) {
        registry.counter("courtpulse.rules.alerts.created", "type", type.name()).increment();
    }

    @Override
    public void duplicateSuppressed(RuleType type) {
        registry.counter("courtpulse.rules.duplicates.suppressed", "type", type.name()).increment();
    }

    @Override
    public void quotaRejected() {
        registry.counter("courtpulse.rules.quota.rejected").increment();
    }
}
