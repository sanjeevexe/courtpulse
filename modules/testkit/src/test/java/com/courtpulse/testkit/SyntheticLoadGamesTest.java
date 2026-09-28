package com.courtpulse.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameReducer;
import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.game.GameStatus;
import com.courtpulse.domain.replay.StateChecksum;
import com.courtpulse.providers.fixture.LoadedFixture;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticLoadGamesTest {
    @Test
    void everyGeneratedGameReplaysToAFinalDeterministically() {
        List<LoadedFixture> first = SyntheticLoadGames.generate("load", 50, 40, 7);
        List<LoadedFixture> again = SyntheticLoadGames.generate("load", 50, 40, 7);
        assertEquals(50, first.size());
        for (int index = 0; index < first.size(); index++) {
            String checksum = replay(first.get(index));
            assertEquals(checksum, replay(again.get(index)), "same seed, same game");
        }
        assertNotEquals(replay(first.getFirst()), replay(SyntheticLoadGames.generate("load", 1, 40, 8).getFirst()));
        GameState state = state(first.getFirst());
        assertEquals(GameStatus.FINAL, state.status());
        for (int index = 0; index < first.size(); index++) {
            assertTrue(state(first.get(index)).pointsFor(SyntheticLoadGames.starPlayer("load", index + 1)) >= 2,
                    "every star scores at least once");
        }
        assertEquals(2_000, first.stream().mapToLong(fixture -> fixture.sourceEvents().size()).sum());
    }

    @Test
    void rejectsUnboundedOrUnsafeRequests() {
        assertThrows(IllegalArgumentException.class, () -> SyntheticLoadGames.generate("Bad Prefix", 1, 40, 1));
        assertThrows(IllegalArgumentException.class, () -> SyntheticLoadGames.generate("load", 5_000, 40, 1));
        assertThrows(IllegalArgumentException.class, () -> SyntheticLoadGames.generate("load", 1, 3, 1));
    }

    private static String replay(LoadedFixture fixture) {
        return StateChecksum.sha256(state(fixture));
    }

    private static GameState state(LoadedFixture fixture) {
        GameState state = GameState.initial(fixture.game().gameId(), fixture.game().homeTeamId(),
                fixture.game().awayTeamId());
        for (CanonicalEvent event : fixture.events()) {
            state = GameReducer.apply(state, event);
        }
        return state;
    }
}
