package com.courtpulse.domain.game;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventIdentity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record GameState(
        String gameId,
        String homeTeamId,
        String awayTeamId,
        GameStatus status,
        int period,
        long clockMillisRemaining,
        int homeScore,
        int awayScore,
        long lastAppliedSequence,
        Map<String, Integer> playerPoints,
        List<CanonicalEvent> recentEvents,
        Map<EventIdentity, String> appliedEventFingerprints) {

    public GameState {
        Objects.requireNonNull(gameId, "gameId is required");
        Objects.requireNonNull(homeTeamId, "homeTeamId is required");
        Objects.requireNonNull(awayTeamId, "awayTeamId is required");
        Objects.requireNonNull(status, "status is required");
        if (gameId.isBlank() || homeTeamId.isBlank() || awayTeamId.isBlank()) {
            throw new IllegalArgumentException("Game and team IDs must not be blank");
        }
        if (homeTeamId.equals(awayTeamId)) {
            throw new IllegalArgumentException("Home and away teams must be different");
        }
        if (period < 0 || period > GamePeriods.MAXIMUM_PERIOD) {
            throw new IllegalArgumentException(
                    "period must be between 0 and " + GamePeriods.MAXIMUM_PERIOD);
        }
        if (clockMillisRemaining < 0 || homeScore < 0 || awayScore < 0 || lastAppliedSequence < 0) {
            throw new IllegalArgumentException("Game state numeric values must be non-negative");
        }
        playerPoints = Map.copyOf(new LinkedHashMap<>(playerPoints));
        if (playerPoints.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getValue() == null
                        || entry.getValue() < 0)) {
            throw new IllegalArgumentException("Player point totals require non-blank IDs and non-negative values");
        }
        recentEvents = List.copyOf(recentEvents);
        appliedEventFingerprints = Map.copyOf(new LinkedHashMap<>(appliedEventFingerprints));
        if (appliedEventFingerprints.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null
                        || entry.getValue() == null
                        || !entry.getValue().matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "Applied event fingerprints require identities and lowercase SHA-256 values");
        }
    }

    public static GameState initial(String gameId, String homeTeamId, String awayTeamId) {
        return new GameState(
                gameId,
                homeTeamId,
                awayTeamId,
                GameStatus.SCHEDULED,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                List.of(),
                Map.of());
    }

    public int pointsFor(String playerId) {
        return playerPoints.getOrDefault(playerId, 0);
    }

    public Set<EventIdentity> appliedEventIdentities() {
        return appliedEventFingerprints.keySet();
    }
}
