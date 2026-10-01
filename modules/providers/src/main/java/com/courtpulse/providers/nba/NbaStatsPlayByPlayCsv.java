package com.courtpulse.providers.nba;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the stats.nba.com "playbyplayv3" CSV layout (as published by the open nba_data project)
 * and normalizes each game into the live-data {@link NbaAction} shape, so replay treats both
 * layouts alike. This layout is complete for seasons where the live-data export stops early, but
 * it differs in three ways that normalization handles:
 *
 * <ul>
 *   <li>Actions are ordered by {@code actionId}; {@code actionNumber} repeats within a game.</li>
 *   <li>Scores appear only on some rows, and instant-replay rows can carry a stale score, so the
 *       running score is taken from scoring and period rows only, and points are the change.</li>
 *   <li>There are no per-action timestamps. Each game's date comes from the caller and each
 *       period's wall-clock start from its period row ("Start of 1st Period (8:44 PM EST)");
 *       within a period, actions are paced by game-clock time plus {@link #STOPPAGE} each. That
 *       allowance is calibrated so these games run as long as live-data replays of the same
 *       games (whose long breaks are shortened), so replays are paced from the game clock, not
 *       to the second.</li>
 * </ul>
 */
public final class NbaStatsPlayByPlayCsv {
    private static final List<String> REQUIRED = List.of(
            "gameId", "actionId", "clock", "period", "actionType", "scoreHome", "scoreAway", "description");
    private static final Set<String> SCORE_ROWS = Set.of("Made Shot", "Free Throw", "Heave", "period");
    private static final Pattern CLOCK = Pattern.compile("^PT(\\d{1,2})M(\\d{1,2})(?:\\.(\\d{1,3})\\d*)?S$");
    private static final Pattern WALL_TIME = Pattern.compile("\\((\\d{1,2}):(\\d{2}) ([AP]M) E[SD]?T\\)");
    private static final ZoneId LEAGUE_ZONE = ZoneId.of("America/New_York");
    /** Game time per period: 12 minutes in regulation, 5 in overtime. */
    private static final long REGULATION_MILLIS = 12 * 60_000L;
    private static final long OVERTIME_MILLIS = 5 * 60_000L;
    /**
     * Time given to each action on top of the game clock (fouls, free throws, substitutions). At
     * 6.5 s, the 60 games in both layouts of the 2026 playoffs replay within about 4 minutes of
     * their timestamped length on average, with no systematic bias.
     */
    static final Duration STOPPAGE = Duration.ofMillis(6_500);
    /** Used only when a period row carries no wall-clock time. */
    static final LocalTime DEFAULT_TIPOFF = LocalTime.of(19, 30);

    private NbaStatsPlayByPlayCsv() {}

    /** One game's rows in provider order, ready to normalize once its date is known. */
    public record Game(String nbaGameId, List<Row> rows) {
        public Game {
            rows = List.copyOf(rows);
        }

        /** The game as live-data actions, ending with a game-end action; throws if it cannot be. */
        public List<NbaAction> actions(LocalDate gameDate) {
            if (gameDate == null) {
                throw new IllegalArgumentException("no game date");
            }
            return normalize(nbaGameId, rows, gameDate);
        }
    }

    /** One stats.nba.com row, reduced to the fields replay uses. Scores are blank when not reported. */
    public record Row(
            long actionId,
            String clock,
            int period,
            String actionType,
            String subType,
            long personId,
            String playerNameI,
            long teamId,
            String teamTricode,
            String scoreHome,
            String scoreAway,
            int shotValue,
            String description) {}

    public static Map<String, Game> read(Reader source) throws IOException {
        BufferedReader reader = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        List<String> header = NbaPlayByPlayCsv.record(reader);
        if (header == null) {
            throw new IllegalArgumentException("Play-by-play CSV is empty");
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 0; index < header.size(); index++) {
            columns.putIfAbsent(header.get(index).strip().replace("﻿", ""), index);
        }
        for (String column : REQUIRED) {
            if (!columns.containsKey(column)) {
                throw new IllegalArgumentException("Play-by-play CSV is missing column " + column);
            }
        }
        Map<String, List<Row>> rows = new LinkedHashMap<>();
        long line = 1;
        for (List<String> fields = NbaPlayByPlayCsv.record(reader); fields != null;
                fields = NbaPlayByPlayCsv.record(reader)) {
            line++;
            if (fields.size() == 1 && fields.getFirst().isBlank()) {
                continue;
            }
            try {
                String gameId = text(fields, columns, "gameId");
                if (!gameId.matches("\\d{1,10}")) {
                    throw new IllegalArgumentException("gameId must be numeric");
                }
                rows.computeIfAbsent("0".repeat(Math.max(0, 10 - gameId.length())) + gameId,
                        key -> new ArrayList<>()).add(row(fields, columns));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid play-by-play row near line " + line + ": "
                        + exception.getMessage(), exception);
            }
        }
        Map<String, Game> games = new LinkedHashMap<>();
        rows.forEach((id, gameRows) -> {
            gameRows.sort(Comparator.comparingLong(Row::actionId));
            games.put(id, new Game(id, gameRows));
        });
        return games;
    }

    /**
     * Game dates from a stats.nba.com shot-detail CSV ({@code GAME_ID}, {@code GAME_DATE} as
     * yyyyMMdd), the companion dataset that dates this layout's games.
     */
    public static Map<String, LocalDate> readGameDates(Reader source) throws IOException {
        BufferedReader reader = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        List<String> header = NbaPlayByPlayCsv.record(reader);
        if (header == null) {
            throw new IllegalArgumentException("Game date CSV is empty");
        }
        int idColumn = header.indexOf("GAME_ID");
        int dateColumn = header.indexOf("GAME_DATE");
        if (idColumn < 0 || dateColumn < 0) {
            throw new IllegalArgumentException("Game date CSV needs GAME_ID and GAME_DATE columns");
        }
        Map<String, LocalDate> dates = new HashMap<>();
        for (List<String> fields = NbaPlayByPlayCsv.record(reader); fields != null;
                fields = NbaPlayByPlayCsv.record(reader)) {
            if (fields.size() <= Math.max(idColumn, dateColumn)) {
                continue;
            }
            String id = fields.get(idColumn).strip();
            String date = fields.get(dateColumn).strip();
            if (id.matches("\\d{1,10}") && date.matches("\\d{8}")) {
                try {
                    dates.putIfAbsent("0".repeat(10 - id.length()) + id, LocalDate.of(
                            Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(4, 6)),
                            Integer.parseInt(date.substring(6, 8))));
                } catch (DateTimeException exception) {
                    // An impossible date dates nothing; that game is reported as having no date.
                }
            }
        }
        return dates;
    }

    /** Peeks at a CSV's header (without consuming it) to tell whether it is this layout. */
    public static boolean detect(BufferedReader reader) throws IOException {
        reader.mark(1 << 16);
        List<String> header = NbaPlayByPlayCsv.record(reader);
        reader.reset();
        return header != null && matches(header);
    }

    /** True when a CSV header is this layout rather than the live-data one. */
    public static boolean matches(List<String> header) {
        List<String> names = header.stream().map(name -> name.strip().replace("﻿", "")).toList();
        return names.contains("actionId") && !names.contains("orderNumber");
    }

    private static Row row(List<String> fields, Map<String, Integer> columns) {
        return new Row(
                number(fields, columns, "actionId"),
                text(fields, columns, "clock"),
                Math.toIntExact(number(fields, columns, "period")),
                text(fields, columns, "actionType"),
                text(fields, columns, "subType"),
                number(fields, columns, "personId"),
                text(fields, columns, "playerNameI"),
                number(fields, columns, "teamId"),
                text(fields, columns, "teamTricode"),
                text(fields, columns, "scoreHome"),
                text(fields, columns, "scoreAway"),
                Math.toIntExact(number(fields, columns, "shotValue")),
                text(fields, columns, "description"));
    }

    static List<NbaAction> normalize(String nbaGameId, List<Row> rows, LocalDate gameDate) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("too few actions");
        }
        List<Instant> times = times(rows, gameDate);
        Set<Long> teams = new HashSet<>();
        rows.stream().filter(row -> row.teamId() > 0).forEach(row -> teams.add(row.teamId()));
        List<NbaAction> actions = new ArrayList<>(rows.size() + 1);
        int home = 0;
        int away = 0;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            int nextHome = home;
            int nextAway = away;
            if (SCORE_ROWS.contains(row.actionType()) && !row.scoreHome().isEmpty() && !row.scoreAway().isEmpty()) {
                nextHome = score(row.scoreHome());
                nextAway = score(row.scoreAway());
            }
            int points = (nextHome - home) + (nextAway - away);
            home = nextHome;
            away = nextAway;
            actions.add(action(nbaGameId, row, times.get(index), home, away, points, teams));
        }
        Row last = rows.getLast();
        actions.add(new NbaAction(nbaGameId, last.actionId() + 1, last.actionId() + 1, "PT00M00.00S",
                times.getLast().toString(), last.period(), "game", "end", 0, "", 0, "", home, away, "", "Game End"));
        return actions;
    }

    /** One row in the live-data vocabulary, so {@link NbaAction#points()} equals the score change. */
    private static NbaAction action(String gameId, Row row, Instant at, int home, int away, int points,
            Set<Long> teams) {
        String type;
        String subType = row.subType();
        String result = "";
        if ("period".equals(row.actionType())) {
            type = "period";
            subType = row.subType().toLowerCase(Locale.ROOT);
        } else if (points > 0) {
            type = points == 1 ? "freethrow" : points == 3 ? "3pt" : "2pt";
            result = "Made";
        } else if ("Free Throw".equals(row.actionType())) {
            type = "freethrow";
            result = "Missed";
        } else if (Set.of("Made Shot", "Missed Shot", "Heave").contains(row.actionType())) {
            type = row.shotValue() == 3 ? "3pt" : "2pt";
            result = "Missed";
        } else {
            type = row.actionType().toLowerCase(Locale.ROOT).replace(" ", "");
        }
        long personId = row.personId();
        long teamId = row.teamId();
        // Team rebounds and turnovers put the team's ID in the player column.
        if (row.playerNameI().isBlank() && teams.contains(personId)) {
            teamId = teamId > 0 ? teamId : personId;
            personId = 0;
        }
        return new NbaAction(gameId, row.actionId(), row.actionId(), row.clock(), at.toString(), row.period(),
                type, subType, personId, row.playerNameI(), teamId, row.teamTricode(), home, away, result,
                row.description());
    }

    /**
     * Wall-clock time for every row. Each period starts at its reported start (never before the
     * previous period ends) and advances by game time elapsed plus {@link #STOPPAGE} per action.
     */
    private static List<Instant> times(List<Row> rows, LocalDate gameDate) {
        List<Instant> times = new ArrayList<>(rows.size());
        Instant previousEnd = null;
        LocalDateTime lastWall = null;
        int start = 0;
        while (start < rows.size()) {
            int period = rows.get(start).period();
            int end = start;
            while (end < rows.size() && rows.get(end).period() == period) {
                end++;
            }
            List<Row> segment = rows.subList(start, end);
            long periodMillis = period <= 4 ? REGULATION_MILLIS : OVERTIME_MILLIS;
            long[] weights = new long[segment.size()];
            long elapsedBefore = 0;
            for (int index = 0; index < segment.size(); index++) {
                long elapsed = Math.max(elapsedBefore, periodMillis - clockMillis(segment.get(index).clock()));
                weights[index] = (elapsed - elapsedBefore) + (index == 0 ? 0 : STOPPAGE.toMillis());
                elapsedBefore = elapsed;
            }
            LocalDateTime wallStart = null;
            for (Row row : segment) {
                if ("period".equals(row.actionType()) && "start".equalsIgnoreCase(row.subType())) {
                    LocalTime time = wallTime(row.description());
                    if (time != null) {
                        wallStart = after(lastWall, gameDate, time);
                        lastWall = wallStart;
                        break;
                    }
                }
            }
            Instant from = wallStart != null ? wallStart.atZone(LEAGUE_ZONE).toInstant()
                    : previousEnd != null ? previousEnd
                    : gameDate.atTime(DEFAULT_TIPOFF).atZone(LEAGUE_ZONE).toInstant();
            if (previousEnd != null && from.isBefore(previousEnd)) {
                from = previousEnd;
            }
            long cumulative = 0;
            for (long weight : weights) {
                cumulative += weight;
                times.add(from.plusMillis(cumulative));
            }
            if (wallStart == null) {
                lastWall = LocalDateTime.ofInstant(times.getLast(), LEAGUE_ZONE);
            }
            previousEnd = times.getLast();
            start = end;
        }
        return times;
    }

    /** The first time of day at or after the previous wall time (games can run past midnight). */
    private static LocalDateTime after(LocalDateTime previous, LocalDate gameDate, LocalTime time) {
        LocalDateTime at = (previous == null ? gameDate : previous.toLocalDate()).atTime(time);
        if (previous != null && at.isBefore(previous.minusHours(1))) {
            at = at.plusDays(1);
        }
        return at;
    }

    static LocalTime wallTime(String description) {
        Matcher match = WALL_TIME.matcher(description == null ? "" : description);
        if (!match.find()) {
            return null;
        }
        int hour = Integer.parseInt(match.group(1)) % 12 + ("PM".equals(match.group(3)) ? 12 : 0);
        int minute = Integer.parseInt(match.group(2));
        return hour < 24 && minute < 60 ? LocalTime.of(hour, minute) : null;
    }

    static long clockMillis(String clock) {
        Matcher match = CLOCK.matcher(clock == null ? "" : clock.strip());
        if (!match.matches()) {
            throw new IllegalArgumentException("invalid clock " + clock);
        }
        String fraction = match.group(3) == null ? "0" : (match.group(3) + "00").substring(0, 3);
        return Long.parseLong(match.group(1)) * 60_000 + Long.parseLong(match.group(2)) * 1_000
                + Long.parseLong(fraction);
    }

    private static int score(String value) {
        try {
            return Integer.parseInt(value.contains(".") ? value.substring(0, value.indexOf('.')) : value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("score is not a number");
        }
    }

    private static String text(List<String> fields, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= fields.size() ? "" : fields.get(index).strip();
    }

    private static long number(List<String> fields, Map<String, Integer> columns, String name) {
        String value = text(fields, columns, name);
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return value.contains(".") ? (long) Double.parseDouble(value) : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " is not a number");
        }
    }
}
