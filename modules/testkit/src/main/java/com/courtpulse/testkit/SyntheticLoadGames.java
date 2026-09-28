package com.courtpulse.testkit;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventType;
import com.courtpulse.domain.event.Score;
import com.courtpulse.providers.fixture.FixtureGame;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.providers.fixture.LoadedSourceEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/**
 * Deterministic, fictional games for load tests: valid scoring sequences across four quarters,
 * one star player per home team (for hot-game rule fanout), and a final. Same seed, same games.
 */
public final class SyntheticLoadGames {
    public static final String SOURCE = "courtpulse-load";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private SyntheticLoadGames() {}

    public static String gameId(String prefix, int index) {
        return "%s-game-%04d".formatted(prefix, index);
    }

    public static String starPlayer(String prefix, int index) {
        return "%s-star-%04d".formatted(prefix, index);
    }

    public static List<LoadedFixture> generate(String prefix, int games, int eventsPerGame, long seed) {
        if (!prefix.matches("[a-z][a-z0-9-]{0,20}")) {
            throw new IllegalArgumentException("prefix must be short lowercase letters, digits, or dashes");
        }
        if (games < 1 || games > 1_000 || eventsPerGame < 8 || eventsPerGame > 2_000) {
            throw new IllegalArgumentException("games must be 1..1000 and events per game 8..2000");
        }
        List<LoadedFixture> fixtures = new ArrayList<>(games);
        for (int index = 1; index <= games; index++) {
            fixtures.add(game(prefix, index, eventsPerGame, new Random(seed * 31 + index)));
        }
        return fixtures;
    }

    private static LoadedFixture game(String prefix, int index, int eventsPerGame, Random random) {
        String gameId = gameId(prefix, index);
        String home = "%s-home-%04d".formatted(prefix, index);
        String away = "%s-away-%04d".formatted(prefix, index);
        List<String> homePlayers = List.of(starPlayer(prefix, index),
                "%s-h2-%04d".formatted(prefix, index), "%s-h3-%04d".formatted(prefix, index));
        List<String> awayPlayers = List.of("%s-a1-%04d".formatted(prefix, index),
                "%s-a2-%04d".formatted(prefix, index), "%s-a3-%04d".formatted(prefix, index));
        List<LoadedSourceEvent> events = new ArrayList<>(eventsPerGame);
        int homeScore = 0;
        int awayScore = 0;
        int middle = eventsPerGame - 2;
        for (int sequence = 1; sequence <= eventsPerGame; sequence++) {
            EventType type;
            int period;
            long clock;
            String team = null;
            List<String> participants = List.of();
            int points = 0;
            if (sequence == 1) {
                type = EventType.GAME_STARTED;
                period = 1;
                clock = 720_000;
            } else if (sequence == eventsPerGame) {
                type = EventType.GAME_FINAL;
                period = 4;
                clock = 0;
            } else {
                int position = sequence - 2;
                period = Math.min(4, 1 + position * 4 / middle);
                int withinPeriod = position - (period - 1) * middle / 4;
                int periodLength = Math.max(1, middle / 4);
                clock = Math.max(0, 720_000 - (long) (withinPeriod + 1) * 720_000 / (periodLength + 1));
                int roll = random.nextInt(10);
                if (sequence == 2) {
                    // Every game opens with a star basket, so per-game rule tests are deterministic.
                    points = 2;
                    type = EventType.FIELD_GOAL_MADE;
                    team = home;
                    participants = List.of(homePlayers.getFirst());
                    homeScore += points;
                } else if (roll < 7) {
                    boolean homeScores = random.nextBoolean();
                    points = roll < 4 ? 2 : roll < 6 ? 3 : 1;
                    type = points == 1 ? EventType.FREE_THROW_MADE : EventType.FIELD_GOAL_MADE;
                    team = homeScores ? home : away;
                    List<String> roster = homeScores ? homePlayers : awayPlayers;
                    // The star takes about half of the home team's shots, so hot-game rules fire.
                    participants = List.of(homeScores && random.nextBoolean() ? roster.getFirst()
                            : roster.get(random.nextInt(roster.size())));
                    if (homeScores) {
                        homeScore += points;
                    } else {
                        awayScore += points;
                    }
                } else {
                    type = EventType.PLAY_RECORDED;
                }
            }
            String eventId = "%s-%04d".formatted(gameId, sequence);
            CanonicalEvent event = new CanonicalEvent(eventId, CanonicalEvent.CURRENT_SCHEMA_VERSION, gameId,
                    SOURCE, "%s:%d".formatted(gameId, sequence), sequence, 1, type, period, clock,
                    START.plusSeconds(index * 10_000L + sequence * 20L), team, participants,
                    new Score(homeScore, awayScore), points, null);
            String raw = "{\"eventId\":\"%s\",\"sequence\":%d,\"type\":\"%s\",\"home\":%d,\"away\":%d,\"points\":%d}"
                    .formatted(eventId, sequence, type, homeScore, awayScore, points);
            events.add(new LoadedSourceEvent(event, raw, sha256(raw)));
        }
        return new LoadedFixture(1, "load " + gameId, "Synthetic load game",
                "Generated by SyntheticLoadGames; fictional", new FixtureGame(gameId, home, away), events);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }
}
