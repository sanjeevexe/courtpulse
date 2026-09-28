package com.courtpulse.providers.live;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Spaces requests evenly under a per-minute quota and honors provider Retry-After pauses. A
 * caller never waits longer than {@code maximumWait}; longer pauses fail fast so daemons keep
 * heartbeating instead of silently sleeping through an upstream penalty.
 */
public final class ProviderRateLimiter {
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration spacing;
    private final Duration maximumWait;
    private Instant nextAllowed = Instant.EPOCH;

    public ProviderRateLimiter(int requestsPerMinute, Duration maximumWait, Clock clock, Sleeper sleeper) {
        if (requestsPerMinute < 1 || requestsPerMinute > 6_000) {
            throw new IllegalArgumentException("requestsPerMinute must be between 1 and 6000");
        }
        this.spacing = Duration.ofMillis(Math.ceilDiv(60_000L, requestsPerMinute));
        this.maximumWait = maximumWait;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public void acquire() {
        Duration wait;
        synchronized (this) {
            Instant now = clock.instant();
            Instant slot = nextAllowed.isAfter(now) ? nextAllowed : now;
            wait = Duration.between(now, slot);
            if (wait.compareTo(maximumWait) > 0) {
                throw new ProviderException("rate_limited_local", true, wait);
            }
            nextAllowed = slot.plus(spacing);
        }
        if (!wait.isZero() && !wait.isNegative()) {
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ProviderException("interrupted", false);
            }
        }
    }

    /** Applies an upstream penalty such as a 429 Retry-After. */
    public synchronized void pauseFor(Duration duration) {
        Instant until = clock.instant().plus(duration);
        if (until.isAfter(nextAllowed)) {
            nextAllowed = until;
        }
    }

    public Duration spacing() {
        return spacing;
    }
}
