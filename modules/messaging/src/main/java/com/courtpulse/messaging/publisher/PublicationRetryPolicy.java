package com.courtpulse.messaging.publisher;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;

public final class PublicationRetryPolicy {
    private final Duration initialDelay;
    private final Duration maximumDelay;
    private final int maximumAttempts;
    private final DoubleSupplier jitter;

    public PublicationRetryPolicy(
            Duration initialDelay,
            Duration maximumDelay,
            int maximumAttempts,
            DoubleSupplier jitter) {
        this.initialDelay = positive(initialDelay, "initialDelay");
        this.maximumDelay = positive(maximumDelay, "maximumDelay");
        if (maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maximumDelay must be at least initialDelay");
        }
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be positive");
        }
        this.maximumAttempts = maximumAttempts;
        this.jitter = Objects.requireNonNull(jitter, "jitter is required");
    }

    public RetryDecision afterFailure(int attempt, boolean retryable) {
        if (!retryable || attempt >= maximumAttempts) {
            return RetryDecision.terminal();
        }
        int exponent = Math.min(30, Math.max(0, attempt - 1));
        long cap = maximumDelay.toMillis();
        long exponential;
        try {
            exponential = Math.multiplyExact(initialDelay.toMillis(), 1L << exponent);
        } catch (ArithmeticException exception) {
            exponential = cap;
        }
        long bounded = Math.min(cap, exponential);
        double sample = jitter.getAsDouble();
        if (sample < 0.0 || sample > 1.0) {
            throw new IllegalStateException("Retry jitter must be between 0 and 1");
        }
        long half = bounded / 2;
        long delay = half + Math.round(half * sample);
        return new RetryDecision(true, Duration.ofMillis(Math.max(1, delay)));
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
