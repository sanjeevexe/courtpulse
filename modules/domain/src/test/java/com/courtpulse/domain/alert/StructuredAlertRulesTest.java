package com.courtpulse.domain.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventType;
import com.courtpulse.domain.event.Score;
import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.game.GameStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredAlertRulesTest {
    @Test
    void playerMilestoneTriggersOnlyOnThresholdCrossing() {
        PlayerMilestoneRule rule = new PlayerMilestoneRule("rule", "owner", "player", 10);

        assertFalse(rule.evaluate(facts(state(8, 7, 4, 20_000, GameStatus.LIVE, Map.of("player", 9)),
                state(10, 7, 4, 19_000, GameStatus.LIVE, Map.of("player", 9)), 1, null)).isPresent());
        assertTrue(rule.evaluate(facts(state(8, 7, 4, 20_000, GameStatus.LIVE, Map.of("player", 9)),
                state(11, 7, 4, 19_000, GameStatus.LIVE, Map.of("player", 12)), 3, null)).isPresent());
        assertFalse(rule.evaluate(facts(state(11, 7, 4, 18_000, GameStatus.LIVE, Map.of("player", 12)),
                state(13, 7, 4, 17_000, GameStatus.LIVE, Map.of("player", 14)), 2, null)).isPresent());

        RuleEvaluationFacts exactFreeThrow = facts(
                state(8, 7, 4, 20_000, GameStatus.LIVE, Map.of("player", 9)),
                state(9, 7, 4, 19_000, GameStatus.LIVE, Map.of("player", 10)),
                1, EventType.FREE_THROW_MADE, "home", "player", null);
        var exact = rule.evaluate(exactFreeThrow).orElseThrow();
        assertEquals(exact.triggerKey(), rule.evaluate(exactFreeThrow).orElseThrow().triggerKey());
        assertEquals("10", exact.context().get("verifiedTotal"));
    }

    @Test
    void closeGameUsesExactPeriodClockAndEntryTransitions() {
        CloseGameRule rule = new CloseGameRule("rule", "owner", 3, 4, 120_000);
        GameState outside = state(80, 70, 4, 120_001, GameStatus.LIVE, Map.of());
        GameState boundary = state(80, 77, 4, 120_000, GameStatus.LIVE, Map.of());
        GameState stillClose = state(82, 79, 4, 90_000, GameStatus.LIVE, Map.of());
        GameState exited = state(86, 79, 4, 70_000, GameStatus.LIVE, Map.of());
        GameState reentered = state(86, 84, 4, 60_000, GameStatus.LIVE, Map.of());

        var first = rule.evaluate(facts(outside, boundary, 3, null)).orElseThrow();
        assertFalse(rule.evaluate(facts(boundary, stillClose, 2, null)).isPresent());
        assertFalse(rule.evaluate(facts(stillClose, exited, 3, null)).isPresent());
        var second = rule.evaluate(facts(exited, reentered, 2, null)).orElseThrow();
        assertNotEquals(first.triggerKey(), second.triggerKey());
        assertFalse(rule.evaluate(facts(outside,
                state(80, 77, 3, 10_000, GameStatus.LIVE, Map.of()), 3, null)).isPresent());
        assertFalse(rule.evaluate(facts(outside,
                state(80, 77, 4, 0, GameStatus.FINAL, Map.of()), 3, null)).isPresent());
        assertFalse(rule.evaluate(facts(outside,
                state(80, 76, 4, 120_000, GameStatus.LIVE, Map.of()), 2, null)).isPresent());
        assertFalse(rule.evaluate(facts(outside,
                state(80, 77, 4, 120_000, GameStatus.SCHEDULED, Map.of()), 2, null)).isPresent());
        assertEquals(first.triggerKey(), rule.evaluate(facts(outside, boundary, 3, null))
                .orElseThrow().triggerKey());
    }

    @Test
    void scoringRunTriggersOnCrossingAndIdentifiesSeparateRuns() {
        ScoringRunRule rule = new ScoringRunRule("rule", "owner", "home", 8);
        RuleEvaluationFacts below = facts(state(5, 2, 2, 400_000, GameStatus.LIVE, Map.of()),
                state(7, 2, 2, 390_000, GameStatus.LIVE, Map.of()), 2,
                new ScoringRunFacts("home", 5, 7, 4));
        RuleEvaluationFacts crossed = facts(state(7, 2, 2, 390_000, GameStatus.LIVE, Map.of()),
                state(9, 2, 2, 380_000, GameStatus.LIVE, Map.of()), 2,
                new ScoringRunFacts("home", 7, 9, 4));
        RuleEvaluationFacts later = facts(state(9, 2, 2, 380_000, GameStatus.LIVE, Map.of()),
                state(12, 2, 2, 370_000, GameStatus.LIVE, Map.of()), 3,
                new ScoringRunFacts("home", 9, 12, 4));
        RuleEvaluationFacts separateRun = facts(state(14, 8, 3, 300_000, GameStatus.LIVE, Map.of()),
                state(17, 8, 3, 290_000, GameStatus.LIVE, Map.of()), 3,
                new ScoringRunFacts("home", 6, 9, 20));

        assertFalse(rule.evaluate(below).isPresent());
        var first = rule.evaluate(crossed).orElseThrow();
        assertFalse(rule.evaluate(later).isPresent());
        var second = rule.evaluate(separateRun).orElseThrow();
        assertNotEquals(first.triggerKey(), second.triggerKey());
        assertEquals("20", second.context().get("runStartSequence"));
    }

    @Test
    void scoringRunsSupportFreeThrowsFieldGoalsAndOpponentTermination() {
        ScoringRunRule rule = new ScoringRunRule("rule", "owner", "home", 5);
        RuleEvaluationFacts fieldGoalBelow = facts(
                state(2, 0, 1, 700_000, GameStatus.LIVE, Map.of()),
                state(4, 0, 1, 690_000, GameStatus.LIVE, Map.of()),
                2, EventType.FIELD_GOAL_MADE, "home", "player", new ScoringRunFacts("home", 2, 4, 1));
        RuleEvaluationFacts freeThrowCrossing = facts(
                state(4, 0, 1, 690_000, GameStatus.LIVE, Map.of()),
                state(5, 0, 1, 680_000, GameStatus.LIVE, Map.of()),
                1, EventType.FREE_THROW_MADE, "home", "player", new ScoringRunFacts("home", 4, 5, 1));
        RuleEvaluationFacts opponentScore = facts(
                state(5, 0, 1, 680_000, GameStatus.LIVE, Map.of()),
                state(5, 2, 1, 670_000, GameStatus.LIVE, Map.of()),
                2, EventType.FIELD_GOAL_MADE, "away", "opponent", new ScoringRunFacts("away", 0, 2, 3));
        RuleEvaluationFacts newRun = facts(
                state(8, 2, 1, 650_000, GameStatus.LIVE, Map.of()),
                state(10, 2, 1, 640_000, GameStatus.LIVE, Map.of()),
                2, EventType.FIELD_GOAL_MADE, "home", "player", new ScoringRunFacts("home", 3, 5, 4));

        assertFalse(rule.evaluate(fieldGoalBelow).isPresent());
        var first = rule.evaluate(freeThrowCrossing).orElseThrow();
        assertFalse(rule.evaluate(opponentScore).isPresent());
        var second = rule.evaluate(newRun).orElseThrow();
        assertEquals(first.triggerKey(), rule.evaluate(freeThrowCrossing).orElseThrow().triggerKey());
        assertNotEquals(first.triggerKey(), second.triggerKey());
    }

    @Test
    void invalidParametersAreRejected() {
        assertDoesNotThrow(() -> new PlayerMilestoneRule("rule", "owner", "player", 200));
        assertDoesNotThrow(() -> new CloseGameRule("rule", "owner", 20, 4, 720_000));
        assertDoesNotThrow(() -> new ScoringRunRule("rule", "owner", "home", 100));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerMilestoneRule("rule", "owner", "player", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerMilestoneRule("rule", "owner", "player", 201));
        assertThrows(IllegalArgumentException.class,
                () -> new CloseGameRule("rule", "owner", 21, 4, 120_000));
        assertThrows(IllegalArgumentException.class,
                () -> new CloseGameRule("rule", "owner", 3, 4, 720_001));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoringRunRule("rule", "owner", "home", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoringRunRule("rule", "owner", "home", 101));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoringRunFacts("home", -1, 1, 1));
        assertDoesNotThrow(() -> new ScoringRunFacts("home", 1_001, 1_002, 1));
    }

    private static RuleEvaluationFacts facts(
            GameState previous, GameState next, int points, ScoringRunFacts run) {
        return facts(previous, next, points,
                points > 0 ? EventType.FIELD_GOAL_MADE : EventType.PERIOD_STARTED,
                points > 0 ? "home" : null, points > 0 ? "player" : null, run);
    }

    private static RuleEvaluationFacts facts(
            GameState previous,
            GameState next,
            int points,
            EventType eventType,
            String teamId,
            String participantId,
            ScoringRunFacts run) {
        long sequence = Math.max(1, next.lastAppliedSequence());
        CanonicalEvent event = new CanonicalEvent(
                "event-" + sequence + "-" + next.homeScore(), 1, "game", "test",
                "provider-" + sequence + "-" + next.homeScore(), sequence, 1,
                eventType,
                next.period(), next.clockMillisRemaining(), Instant.parse("2026-01-01T00:00:00Z"),
                teamId, participantId == null ? List.of() : List.of(participantId),
                new Score(next.homeScore(), next.awayScore()), points);
        return new RuleEvaluationFacts(previous, next, event, run);
    }

    private static GameState state(
            int home, int away, int period, long clock, GameStatus status, Map<String, Integer> points) {
        return new GameState(
                "game", "home", "away", status, period, clock, home, away,
                Math.max(1, home + away), points, List.of(), Map.of());
    }
}
