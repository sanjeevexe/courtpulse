package com.courtpulse.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.courtpulse.domain.alert.PlayerMilestoneRule;
import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameStatus;
import com.courtpulse.domain.replay.ReplayEngine;
import com.courtpulse.domain.replay.ReplayResult;
import com.courtpulse.providers.fixture.LoadedFixture;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticReplayTest {
    private static final PlayerMilestoneRule RULE =
            new PlayerMilestoneRule("milestone-player-ace-10", "player_ace", 10);

    @Test
    void fixtureReplaysDeterministicallyWithExpectedFinalState() {
        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        ReplayEngine engine = new ReplayEngine();

        ReplayResult first = engine.replay(fixture.game().gameId(), fixture.events(), List.of(RULE));
        ReplayResult second = engine.replay(fixture.game().gameId(), fixture.events(), List.of(RULE));

        assertEquals(first.finalStateChecksum(), second.finalStateChecksum());
        assertEquals(first.alerts(), second.alerts());
        assertEquals(GameStatus.FINAL, first.finalState().status());
        assertEquals(18, first.finalState().homeScore());
        assertEquals(14, first.finalState().awayScore());
        assertEquals(13, first.finalState().pointsFor("player_ace"));
        assertEquals(20, first.finalState().lastAppliedSequence());
        assertEquals(20, first.acceptedEventCount());
        assertEquals(0, first.suppressedDuplicateCount());
        assertEquals(1, first.alerts().size());
    }

    @Test
    void duplicateInjectionPreservesChecksumAndLogicalAlertSet() {
        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        ReplayEngine engine = new ReplayEngine();
        ReplayResult normal = engine.replay(fixture.game().gameId(), fixture.events(), List.of(RULE));

        List<CanonicalEvent> injected = new ArrayList<>();
        for (CanonicalEvent event : fixture.events()) {
            injected.add(event);
            if (event.sequence() == 4 || event.sequence() == 11) {
                injected.add(event);
            }
        }
        ReplayResult duplicateRun = engine.replay(fixture.game().gameId(), injected, List.of(RULE));

        assertEquals(normal.finalStateChecksum(), duplicateRun.finalStateChecksum());
        assertEquals(normal.alerts(), duplicateRun.alerts());
        assertEquals(20, duplicateRun.acceptedEventCount());
        assertEquals(2, duplicateRun.suppressedDuplicateCount());
        assertEquals(1, duplicateRun.alerts().size());
    }
}
