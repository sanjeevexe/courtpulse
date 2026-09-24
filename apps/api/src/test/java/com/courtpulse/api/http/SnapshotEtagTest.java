package com.courtpulse.api.http;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.courtpulse.query.GameSnapshotReadModel;
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
}
