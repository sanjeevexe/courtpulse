package com.courtpulse.providers.live;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Stops amplifying an upstream outage: open after N consecutive failures, then one probe. */
public final class ProviderCircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openUntil = Instant.EPOCH;
    private boolean probeInFlight;

    public ProviderCircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    public synchronized void beforeRequest() {
        if (state == State.OPEN) {
            if (clock.instant().isBefore(openUntil)) {
                throw new ProviderException("circuit_open", true,
                        Duration.between(clock.instant(), openUntil));
            }
            state = State.HALF_OPEN;
            probeInFlight = false;
        }
        if (state == State.HALF_OPEN) {
            if (probeInFlight) {
                throw new ProviderException("circuit_open", true, openDuration);
            }
            probeInFlight = true;
        }
    }

    public synchronized void recordSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        probeInFlight = false;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        probeInFlight = false;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openUntil = clock.instant().plus(openDuration);
        }
    }

    public synchronized State state() {
        if (state == State.OPEN && !clock.instant().isBefore(openUntil)) {
            return State.HALF_OPEN;
        }
        return state;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }
}
