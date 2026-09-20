package com.courtpulse.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        ReplayResult first = replay(engine, fixture, fixture.events());
        ReplayResult second = replay(engine, fixture, fixture.events());

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
        ReplayResult normal = replay(engine, fixture, fixture.events());

        List<CanonicalEvent> injected = new ArrayList<>();
        for (CanonicalEvent event : fixture.events()) {
            injected.add(event);
            if (event.sequence() == 4 || event.sequence() == 11) {
                injected.add(event);
            }
        }
        ReplayResult duplicateRun = replay(engine, fixture, injected);

        assertEquals(normal.finalStateChecksum(), duplicateRun.finalStateChecksum());
        assertEquals(normal.alerts(), duplicateRun.alerts());
        assertEquals(20, duplicateRun.acceptedEventCount());
        assertEquals(2, duplicateRun.suppressedDuplicateCount());
        assertEquals(1, duplicateRun.alerts().size());
    }

    @Test
    void conflictingDuplicateIsRejectedAfterOriginalLeavesRecentHistory() {
        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        List<CanonicalEvent> events = new ArrayList<>(fixture.events().subList(0, 12));
        CanonicalEvent original = events.getFirst();
        events.add(new CanonicalEvent(
                "conflicting-event-id",
                original.schemaVersion(),
                original.gameId(),
                original.source(),
                original.providerEventId(),
                original.sequence(),
                original.revision(),
                original.type(),
                original.period(),
                original.clockMillisRemaining(),
                original.occurredAt().plusSeconds(1),
                original.teamId(),
                original.participantIds(),
                original.scoreAfter(),
                original.points()));

        assertThrows(IllegalArgumentException.class,
                () -> replay(new ReplayEngine(), fixture, events));
    }

    private static ReplayResult replay(
            ReplayEngine engine, LoadedFixture fixture, List<CanonicalEvent> events) {
        return engine.replay(
                fixture.game().gameId(),
                fixture.game().homeTeamId(),
                fixture.game().awayTeamId(),
                events,
                List.of(RULE));
    }
}
