package com.courtpulse.api.http;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.courtpulse.query.DataStatus;
import com.courtpulse.query.GameSnapshotReadModel;
import com.courtpulse.query.TeamLabels;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotEtagTest {
    @Test
    void checksumChangeInvalidatesValidatorAtSameEventVersion() {
        GameSnapshotReadModel before = mock(GameSnapshotReadModel.class);
        GameSnapshotReadModel corrected = mock(GameSnapshotReadModel.class);
        when(before.gameId()).thenReturn("game-1");
        when(corrected.gameId()).thenReturn("game-1");
        when(before.stateVersion()).thenReturn(20L);
        when(corrected.stateVersion()).thenReturn(20L);
        when(before.stateChecksum()).thenReturn("old-checksum");
        when(corrected.stateChecksum()).thenReturn("corrected-checksum");
        assertNotEquals(SnapshotEtag.of(before), SnapshotEtag.of(corrected));
    }

    @Test
    void staleDataStatusAndLateDisplayNamesInvalidateValidator() {
        GameSnapshotReadModel live = snapshot(DataStatus.LIVE, Map.of());
        GameSnapshotReadModel stale = snapshot(DataStatus.STALE, Map.of());
        GameSnapshotReadModel named = snapshot(DataStatus.LIVE, Map.of("bdl-player-1", "Ada Lane"));
        assertNotEquals(SnapshotEtag.of(live), SnapshotEtag.of(stale));
        assertNotEquals(SnapshotEtag.of(live), SnapshotEtag.of(named));
    }

    private static GameSnapshotReadModel snapshot(DataStatus status, Map<String, String> names) {
        return new GameSnapshotReadModel(
                "game-1", "balldontlie", "home", "away", "LIVE", 2, 300_000, 40, 38,
                Map.of("bdl-player-1", 12), 30, 30, "checksum", List.of(),
                Instant.parse("2026-09-20T16:00:00Z"), status, TeamLabels.UNKNOWN, null, names);
    }
}
