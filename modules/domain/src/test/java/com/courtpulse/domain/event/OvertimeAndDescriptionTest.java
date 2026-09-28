package com.courtpulse.domain.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.courtpulse.domain.alert.CloseGameRule;
import com.courtpulse.domain.game.GamePeriods;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OvertimeAndDescriptionTest {
    @Test
    void overtimePeriodsUseFiveMinuteClocksUpToSixOvertimes() {
        assertDoesNotThrow(() -> event(5, 300_000, null));
        assertDoesNotThrow(() -> event(10, 0, null));
        assertThrows(IllegalArgumentException.class, () -> event(5, 300_001, null));
        assertThrows(IllegalArgumentException.class, () -> event(11, 0, null));
        assertDoesNotThrow(() -> event(4, 720_000, null));
        assertEquals("OT2", GamePeriods.label(6));
        assertEquals("Q4", GamePeriods.label(4));
        assertDoesNotThrow(() -> new CloseGameRule("rule", "owner", 3, 5, 120_000));
        assertThrows(IllegalArgumentException.class, () -> new CloseGameRule("rule", "owner", 3, 11, 0));
    }

    @Test
    void descriptionsAreOptionalBoundedPrintableAndPartOfTheFingerprint() {
        CanonicalEvent withoutText = event(1, 700_000, null);
        CanonicalEvent legacy = new CanonicalEvent("event-1", 1, "game", "source", "provider:1", 1, 1,
                EventType.PLAY_RECORDED, 1, 700_000, Instant.parse("2026-01-01T00:00:00Z"), null,
                List.of(), new Score(0, 0), 0);
        assertEquals(EventFingerprint.sha256(legacy), EventFingerprint.sha256(withoutText),
                "events without provider text keep their existing fingerprints");
        assertNotEquals(EventFingerprint.sha256(withoutText),
                EventFingerprint.sha256(event(1, 700_000, "Ada Lane defensive rebound")));
        assertThrows(IllegalArgumentException.class, () -> event(1, 700_000, " padded "));
        assertThrows(IllegalArgumentException.class, () -> event(1, 700_000, "bell\u0007"));
        assertThrows(IllegalArgumentException.class, () -> event(1, 700_000, "x".repeat(281)));
    }

    private static CanonicalEvent event(int period, long clock, String description) {
        return new CanonicalEvent("event-1", 1, "game", "source", "provider:1", 1, 1,
                EventType.PLAY_RECORDED, period, clock, Instant.parse("2026-01-01T00:00:00Z"), null,
                List.of(), new Score(0, 0), 0, description);
    }
}
