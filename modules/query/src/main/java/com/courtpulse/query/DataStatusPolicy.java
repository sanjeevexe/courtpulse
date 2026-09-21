package com.courtpulse.query;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class DataStatusPolicy {
    private final Clock clock;
    private final Duration liveFreshnessWindow;

    public DataStatusPolicy(Clock clock, Duration liveFreshnessWindow) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.liveFreshnessWindow = Objects.requireNonNull(
                liveFreshnessWindow, "liveFreshnessWindow is required");
        if (liveFreshnessWindow.isNegative() || liveFreshnessWindow.isZero()) {
            throw new IllegalArgumentException("liveFreshnessWindow must be positive");
        }
    }

    public DataStatus derive(String gameStatus, Instant updatedAt, boolean processingBlocked) {
        if (processingBlocked) {
            return DataStatus.PROCESSING_BLOCKED;
        }
        if ("LIVE".equals(gameStatus)
                && updatedAt.plus(liveFreshnessWindow).isBefore(clock.instant())) {
            return DataStatus.STALE;
        }
        return switch (gameStatus) {
            case "SCHEDULED" -> DataStatus.SCHEDULED;
            case "LIVE" -> DataStatus.LIVE;
            case "FINAL" -> DataStatus.FINAL;
            default -> throw new IllegalStateException("Unknown durable game status");
        };
    }
}
