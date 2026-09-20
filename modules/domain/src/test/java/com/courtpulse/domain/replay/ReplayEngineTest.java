package com.courtpulse.domain.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.courtpulse.domain.alert.PlayerMilestoneRule;
import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventType;
import com.courtpulse.domain.event.Score;
import com.courtpulse.domain.game.SequenceViolationException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplayEngineTest {
    private static final String GAME_ID = "game-test";
    private static final String PLAYER_ID = "player-test";
    private final ReplayEngine engine = new ReplayEngine();
    private final PlayerMilestoneRule rule = new PlayerMilestoneRule("rule-10", PLAYER_ID, 10);

    @Test
    void suppressesDuplicateEventsWithoutChangingStateOrDuplicatingAlerts() {
        List<CanonicalEvent> normal = scoringSequence();
        CanonicalEvent milestoneEvent = normal.get(4);
        List<CanonicalEvent> withDuplicate = List.of(
                normal.get(0), normal.get(1), normal.get(2), normal.get(3),
                milestoneEvent, milestoneEvent, normal.get(5));

        ReplayResult expected = engine.replay(GAME_ID, normal, List.of(rule));
        ReplayResult duplicateRun = engine.replay(GAME_ID, withDuplicate, List.of(rule));

        assertEquals(expected.finalStateChecksum(), duplicateRun.finalStateChecksum());
        assertEquals(1, duplicateRun.suppressedDuplicateCount());
        assertEquals(1, duplicateRun.alerts().size());
        assertEquals(expected.alerts(), duplicateRun.alerts());
        assertEquals(12, duplicateRun.finalState().pointsFor(PLAYER_ID));
    }

    @Test
    void milestoneTriggersOnlyWhenTotalCrossesThreshold() {
        ReplayResult beforeCrossing =
                engine.replay(GAME_ID, scoringSequence().subList(0, 4), List.of(rule));
        ReplayResult afterCrossing = engine.replay(GAME_ID, scoringSequence(), List.of(rule));

        assertEquals(0, beforeCrossing.alerts().size());
        assertEquals(8, beforeCrossing.finalState().pointsFor(PLAYER_ID));
        assertEquals(1, afterCrossing.alerts().size());
        assertEquals(
                "PLAYER_MILESTONE:rule-10:game-test:POINTS:10",
                afterCrossing.alerts().getFirst().triggerKey());
    }

    @Test
    void rejectsAnOutOfSequenceEvent() {
        List<CanonicalEvent> events = List.of(
                started(1),
                scored(3, 2, new Score(2, 0)));

        SequenceViolationException exception = assertThrows(
                SequenceViolationException.class,
                () -> engine.replay(GAME_ID, events, List.of(rule)));

        assertEquals("Expected event sequence 2 but received 3", exception.getMessage());
    }

    private static List<CanonicalEvent> scoringSequence() {
        return List.of(
                started(1),
                scored(2, 3, new Score(3, 0)),
                scored(3, 3, new Score(6, 0)),
                scored(4, 2, new Score(8, 0)),
                scored(5, 2, new Score(10, 0)),
                scored(6, 2, new Score(12, 0)));
    }

    private static CanonicalEvent started(long sequence) {
        return new CanonicalEvent(
                "event-" + sequence,
                1,
                GAME_ID,
                "test-source",
                "provider-" + sequence,
                sequence,
                1,
                EventType.GAME_STARTED,
                1,
                720_000,
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                List.of(),
                new Score(0, 0),
                0);
    }

    private static CanonicalEvent scored(long sequence, int points, Score score) {
        return new CanonicalEvent(
                "event-" + sequence,
                1,
                GAME_ID,
                "test-source",
                "provider-" + sequence,
                sequence,
                1,
                EventType.FIELD_GOAL_MADE,
                1,
                700_000 - sequence,
                Instant.parse("2026-01-01T00:00:01Z").plusSeconds(sequence),
                "team-home",
                List.of(PLAYER_ID),
                score,
                points);
    }
}
