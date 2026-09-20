package com.courtpulse.messaging.publisher;

import java.time.Duration;

public record RetryDecision(boolean retry, Duration delay) {
    public static RetryDecision terminal() {
        return new RetryDecision(false, Duration.ZERO);
    }
}
