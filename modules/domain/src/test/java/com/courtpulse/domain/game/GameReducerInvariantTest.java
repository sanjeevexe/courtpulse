package com.courtpulse.domain.game;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.courtpulse.domain.alert.PlayerMilestoneRule;
import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventFingerprint;
import com.courtpulse.domain.event.EventType;
import com.courtpulse.domain.event.Score;
import com.courtpulse.domain.replay.ReplayEngine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameReducerInvariantTest {
    private static final String GAME = "game-1";
    private static final String HOME = "home";
    private static final String AWAY = "away";

    @Test
    void scoringEventChangesExactlyOneTeam() {
        GameState state = liveState();
        assertThrows(IllegalArgumentException.class,
                () -> GameReducer.apply(state, scoreEvent("event-2", GAME, HOME, 2, 2, 2)));
    }

    @Test
    void scoreDeltaMustEqualDeclaredPoints() {
        GameState state = liveState();
        assertThrows(IllegalArgumentException.class,
                () -> GameReducer.apply(state, scoreEvent("event-2", GAME, HOME, 3, 0, 2)));
    }

    @Test
    void scoringTeamMustMatchSideThatIncreased() {
        GameState state = liveState();
        assertThrows(IllegalArgumentException.class,
                () -> GameReducer.apply(state, scoreEvent("event-2", GAME, AWAY, 2, 0, 2)));
    }

    @Test
    void scoresMustNotDecrease() {
        GameState state = new GameState(
                GAME, HOME, AWAY, GameStatus.LIVE, 1, 600_000, 4, 2, 1,
                java.util.Map.of(), List.of(started(GAME)),
                java.util.Map.of(started(GAME).identity(), EventFingerprint.sha256(started(GAME))));
        assertThrows(IllegalArgumentException.class,
                () -> GameReducer.apply(state, scoreEvent("event-2", GAME, HOME, 2, 2, 2)));
    }

    @Test
    void nonScoringEventMustNotChangeScore() {
        GameState state = liveState();
        CanonicalEvent period = new CanonicalEvent(
                "event-2", 1, GAME, "test", "provider-2", 2, 1,
                EventType.PERIOD_STARTED, 2, 720_000, Instant.parse("2026-01-01T00:12:00Z"),
                null, List.of(), new Score(1, 0), 0);
        assertThrows(IllegalArgumentException.class, () -> GameReducer.apply(state, period));
    }

    @Test
    void wrongGameIsRejectedBeforeDuplicateSuppression() {
        CanonicalEvent start = started(GAME);
        CanonicalEvent wrongGameDuplicate = new CanonicalEvent(
                start.eventId(), start.schemaVersion(), "other-game", start.source(),
                start.providerEventId(), start.sequence(), start.revision(), start.type(), start.period(),
                start.clockMillisRemaining(), start.occurredAt(), start.teamId(), start.participantIds(),
                start.scoreAfter(), start.points());
        ReplayEngine engine = new ReplayEngine();

        assertThrows(IllegalArgumentException.class, () -> engine.replay(
                GAME, HOME, AWAY, List.of(start, wrongGameDuplicate),
                List.of(new PlayerMilestoneRule("rule", "player", 10))));
    }

    @Test
    void conflictingDuplicatePayloadIsRejected() {
        CanonicalEvent start = started(GAME);
        CanonicalEvent accepted = scoreEvent("event-2", GAME, HOME, 2, 0, 2);
        CanonicalEvent conflicting = new CanonicalEvent(
                "changed-event-id", 1, GAME, accepted.source(), accepted.providerEventId(),
                accepted.sequence(), accepted.revision(), accepted.type(), accepted.period(),
                accepted.clockMillisRemaining(), accepted.occurredAt(), accepted.teamId(),
                List.of("different-player"), accepted.scoreAfter(), accepted.points());

        assertThrows(IllegalArgumentException.class, () -> new ReplayEngine().replay(
                GAME, HOME, AWAY, List.of(start, accepted, conflicting), List.of()));
    }

    private static GameState liveState() {
        CanonicalEvent start = started(GAME);
        return GameReducer.apply(GameState.initial(GAME, HOME, AWAY), start);
    }

    private static CanonicalEvent started(String gameId) {
        return new CanonicalEvent(
                "event-1", 1, gameId, "test", "provider-1", 1, 1,
                EventType.GAME_STARTED, 1, 720_000, Instant.parse("2026-01-01T00:00:00Z"),
                null, List.of(), new Score(0, 0), 0);
    }

    private static CanonicalEvent scoreEvent(
            String eventId, String gameId, String teamId, int home, int away, int points) {
        return new CanonicalEvent(
                eventId, 1, gameId, "test", "provider-2", 2, 1,
                EventType.FIELD_GOAL_MADE, 1, 650_000, Instant.parse("2026-01-01T00:01:00Z"),
                teamId, List.of("player"), new Score(home, away), points);
    }
}
