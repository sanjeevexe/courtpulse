package com.courtpulse.domain.game;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventFingerprint;
import com.courtpulse.domain.event.EventIdentity;
import com.courtpulse.domain.event.EventType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure game-state transition function. It performs no I/O and reads no clock. */
public final class GameReducer {
    private static final int RECENT_EVENT_LIMIT = 10;

    private GameReducer() {}

    public static GameState apply(GameState state, CanonicalEvent event) {
        validateEventContext(state, event);
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

        Map<EventIdentity, String> nextFingerprints =
                new LinkedHashMap<>(state.appliedEventFingerprints());
        nextFingerprints.put(event.identity(), EventFingerprint.sha256(event));

        return new GameState(
                state.gameId(),
                state.homeTeamId(),
                state.awayTeamId(),
                nextStatus,
                event.period(),
                event.clockMillisRemaining(),
                event.scoreAfter().home(),
                event.scoreAfter().away(),
                event.sequence(),
                nextPlayerPoints,
                nextHistory,
                nextFingerprints);
    }

    /** Validates game metadata and score semantics independently of sequence application. */
    public static void validateEventContext(GameState state, CanonicalEvent event) {
        validateEventMetadata(state, event);

        int homeDelta = event.scoreAfter().home() - state.homeScore();
        int awayDelta = event.scoreAfter().away() - state.awayScore();
        if (homeDelta < 0 || awayDelta < 0) {
            throw new IllegalArgumentException("An event must not decrease either team's score");
        }

        if (isScoringEvent(event.type())) {
            boolean homeScored = homeDelta > 0 && awayDelta == 0;
            boolean awayScored = awayDelta > 0 && homeDelta == 0;
            if (!homeScored && !awayScored) {
                throw new IllegalArgumentException("A scoring event must increase exactly one team's score");
            }
            int scoreDelta = homeScored ? homeDelta : awayDelta;
            if (scoreDelta != event.points()) {
                throw new IllegalArgumentException(
                        "Score delta " + scoreDelta + " does not equal declared points " + event.points());
            }
            String expectedTeam = homeScored ? state.homeTeamId() : state.awayTeamId();
            if (!expectedTeam.equals(event.teamId())) {
                throw new IllegalArgumentException(
                        "Scoring team " + event.teamId() + " does not match score increase for " + expectedTeam);
            }
        } else if (homeDelta != 0 || awayDelta != 0) {
            throw new IllegalArgumentException("A non-scoring event must not change the score");
        }
    }

    /** Validates facts that remain meaningful even for a redelivered older event. */
    public static void validateEventMetadata(GameState state, CanonicalEvent event) {
        if (!state.gameId().equals(event.gameId())) {
            throw new IllegalArgumentException(
                    "Event gameId " + event.gameId() + " does not match state " + state.gameId());
        }
        if (isScoringEvent(event.type())) {
            boolean knownTeam = state.homeTeamId().equals(event.teamId())
                    || state.awayTeamId().equals(event.teamId());
            if (!knownTeam) {
                throw new IllegalArgumentException(
                        "Scoring team " + event.teamId() + " is not part of game " + state.gameId());
            }
        }
    }

    private static boolean isScoringEvent(EventType type) {
        return type == EventType.FIELD_GOAL_MADE || type == EventType.FREE_THROW_MADE;
    }
}
