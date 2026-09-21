package com.courtpulse.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DataStatusPolicyTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:10:00Z");
    private final DataStatusPolicy policy = new DataStatusPolicy(
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(2));

    @Test
    void mapsDurableBasketballStatesExplicitly() {
        assertEquals(DataStatus.SCHEDULED, policy.derive("SCHEDULED", NOW.minusSeconds(600), false));
        assertEquals(DataStatus.LIVE, policy.derive("LIVE", NOW.minusSeconds(30), false));
        assertEquals(DataStatus.FINAL, policy.derive("FINAL", NOW.minusSeconds(600), false));
    }

    @Test
    void staleAppliesOnlyToLiveGames() {
        assertEquals(DataStatus.STALE, policy.derive("LIVE", NOW.minusSeconds(121), false));
        assertEquals(DataStatus.FINAL, policy.derive("FINAL", NOW.minusSeconds(121), false));
    }

    @Test
    void blockedTakesPrecedenceOverFreshnessAndGameStatus() {
        assertEquals(DataStatus.PROCESSING_BLOCKED, policy.derive("LIVE", NOW, true));
        assertEquals(DataStatus.PROCESSING_BLOCKED, policy.derive("FINAL", NOW, true));
    }
}
