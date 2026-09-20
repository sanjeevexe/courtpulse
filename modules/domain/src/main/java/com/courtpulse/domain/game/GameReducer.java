package com.courtpulse.domain.game;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventIdentity;
import com.courtpulse.domain.event.EventType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure game-state transition function. It performs no I/O and reads no clock. */
public final class GameReducer {
    private static final int RECENT_EVENT_LIMIT = 10;

    private GameReducer() {}

    public static GameState apply(GameState state, CanonicalEvent event) {
        if (!state.gameId().equals(event.gameId())) {
            throw new IllegalArgumentException(
                    "Event gameId " + event.gameId() + " does not match state " + state.gameId());
        }
        long expectedSequence = state.lastAppliedSequence() + 1;
        if (event.sequence() != expectedSequence) {
            throw new SequenceViolationException(expectedSequence, event.sequence());
        }
        if (state.status() == GameStatus.FINAL) {
            throw new IllegalStateException("Cannot apply an event after the game is final");
        }

        GameStatus nextStatus = state.status();
        if (event.type() == EventType.GAME_STARTED) {
            if (state.status() != GameStatus.SCHEDULED) {
                throw new IllegalStateException("GAME_STARTED may only be applied to a scheduled game");
            }
            nextStatus = GameStatus.LIVE;
        } else if (event.type() == EventType.GAME_FINAL) {
            nextStatus = GameStatus.FINAL;
        } else if (state.status() != GameStatus.LIVE) {
            throw new IllegalStateException(event.type() + " requires a live game");
        }

        Map<String, Integer> nextPlayerPoints = new LinkedHashMap<>(state.playerPoints());
        if (isScoringEvent(event.type())) {
            String scorerId = event.participantIds().getFirst();
            nextPlayerPoints.merge(scorerId, event.points(), Integer::sum);
        }

        List<CanonicalEvent> nextHistory = new ArrayList<>(state.recentEvents());
        nextHistory.add(event);
        if (nextHistory.size() > RECENT_EVENT_LIMIT) {
            nextHistory = new ArrayList<>(nextHistory.subList(nextHistory.size() - RECENT_EVENT_LIMIT, nextHistory.size()));
        }

        Set<EventIdentity> nextIdentities = new LinkedHashSet<>(state.appliedEventIdentities());
        nextIdentities.add(event.identity());

        return new GameState(
                state.gameId(),
                nextStatus,
                event.period(),
                event.clockMillisRemaining(),
                event.scoreAfter().home(),
                event.scoreAfter().away(),
                event.sequence(),
                nextPlayerPoints,
                nextHistory,
                nextIdentities);
    }

    private static boolean isScoringEvent(EventType type) {
        return type == EventType.FIELD_GOAL_MADE || type == EventType.FREE_THROW_MADE;
    }
}
