package com.courtpulse.providers.nba;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A completed real game prepared for replay: its teams, final score, and every action with a
 * replay offset. Offsets follow the real timestamps, except that any pause longer than
 * {@link #MAXIMUM_GAP} (halftime, reviews, long timeouts) is shortened to it, so a replay spends
 * its time on basketball.
 */
public record NbaReplayGame(
        String nbaGameId,
        LocalDate gameDate,
        Instant startedAt,
        Team home,
        Team away,
        int homeScore,
        int awayScore,
        int periods,
        List<TimedAction> actions) {
    public static final Duration MAXIMUM_GAP = Duration.ofSeconds(45);
    private static final ZoneId LEAGUE_ZONE = ZoneId.of("America/New_York");

    public NbaReplayGame {
        Objects.requireNonNull(nbaGameId, "nbaGameId is required");
        actions = List.copyOf(actions);
    }

    public record Team(long teamId, String tricode, String name) {}

    /** An action with its 1-based position (the canonical sequence) and replay offset. */
    public record TimedAction(int ordinal, long offsetMillis, NbaAction action) {}

    public long durationMillis() {
        return actions.getLast().offsetMillis();
    }

    /**
     * Builds a replayable game from one game's actions in provider order, or explains why it
     * cannot be replayed (for example an unfinished game or scores that do not add up).
     */
    public static NbaReplayGame from(List<NbaAction> ordered) {
        if (ordered.size() < 3) {
            throw new IllegalArgumentException("too few actions");
        }
        NbaAction first = ordered.getFirst();
        if (!first.periodStart() || first.period() != 1) {
            throw new IllegalArgumentException("first action is not the start of period 1");
        }
        if (!ordered.getLast().gameEnd()) {
            throw new IllegalArgumentException("game has no final action");
        }
        Map<Long, String> teams = new LinkedHashMap<>();
        Long homeTeamId = null;
        Long awayTeamId = null;
        int home = 0;
        int away = 0;
        for (NbaAction action : ordered) {
            if (action.teamId() > 0 && !action.teamTricode().isEmpty()) {
                teams.putIfAbsent(action.teamId(), action.teamTricode());
            }
            int homeDelta = action.scoreHome() - home;
            int awayDelta = action.scoreAway() - away;
            if (homeDelta < 0 || awayDelta < 0 || (homeDelta > 0 && awayDelta > 0)
                    || homeDelta + awayDelta != action.points()) {
                throw new IllegalArgumentException("scores do not add up at action " + action.actionNumber());
            }
            if (homeDelta > 0 && homeTeamId == null) {
                homeTeamId = action.teamId();
            }
            if (awayDelta > 0 && awayTeamId == null) {
                awayTeamId = action.teamId();
            }
            home = action.scoreHome();
            away = action.scoreAway();
        }
        if (teams.size() != 2) {
            throw new IllegalArgumentException("expected two teams, found " + teams.size());
        }
        List<Long> ids = new ArrayList<>(teams.keySet());
        if (homeTeamId == null && awayTeamId == null) {
            throw new IllegalArgumentException("neither team scored");
        }
        long homeId = homeTeamId != null ? homeTeamId : ids.get(0).equals(awayTeamId) ? ids.get(1) : ids.get(0);
        long awayId = ids.get(0) == homeId ? ids.get(1) : ids.get(0);
        if (awayTeamId != null && awayTeamId != awayId) {
            throw new IllegalArgumentException("home and away teams are inconsistent");
        }

        Instant startedAt = instant(first.timeActual());
        List<TimedAction> timed = new ArrayList<>(ordered.size());
        Instant previous = startedAt;
        long offset = 0;
        int periods = 1;
        for (int index = 0; index < ordered.size(); index++) {
            NbaAction action = ordered.get(index);
            Instant at = instant(action.timeActual());
            long gap = Math.max(0, Duration.between(previous, at).toMillis());
            offset += Math.min(gap, MAXIMUM_GAP.toMillis());
            if (at.isAfter(previous)) {
                previous = at;
            }
            periods = Math.max(periods, action.period());
            timed.add(new TimedAction(index + 1, offset, action));
        }
        return new NbaReplayGame(first.nbaGameId(), startedAt.atZone(LEAGUE_ZONE).toLocalDate(), startedAt,
                new Team(homeId, teams.get(homeId), NbaTeams.name(teams.get(homeId))),
                new Team(awayId, teams.get(awayId), NbaTeams.name(teams.get(awayId))),
                home, away, periods, timed);
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("invalid timeActual " + value);
        }
    }
}
