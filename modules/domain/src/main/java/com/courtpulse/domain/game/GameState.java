package com.courtpulse.domain.game;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventIdentity;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record GameState(
        String gameId,
        GameStatus status,
        int period,
        long clockMillisRemaining,
        int homeScore,
        int awayScore,
        long lastAppliedSequence,
        Map<String, Integer> playerPoints,
        List<CanonicalEvent> recentEvents,
        Set<EventIdentity> appliedEventIdentities) {

    public GameState {
        Objects.requireNonNull(gameId, "gameId is required");
        Objects.requireNonNull(status, "status is required");
        if (gameId.isBlank()) {
            throw new IllegalArgumentException("gameId must not be blank");
        }
        if (period < 0 || period > 4) {
            throw new IllegalArgumentException("period must be between 0 and 4");
        }
        if (clockMillisRemaining < 0 || homeScore < 0 || awayScore < 0 || lastAppliedSequence < 0) {
            throw new IllegalArgumentException("Game state numeric values must be non-negative");
        }
        playerPoints = Map.copyOf(new LinkedHashMap<>(playerPoints));
        recentEvents = List.copyOf(recentEvents);
        appliedEventIdentities = Set.copyOf(new LinkedHashSet<>(appliedEventIdentities));
    }

    public static GameState initial(String gameId) {
        return new GameState(
                gameId,
                GameStatus.SCHEDULED,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                List.of(),
                Set.of());
    }

    public int pointsFor(String playerId) {
        return playerPoints.getOrDefault(playerId, 0);
    }
}
