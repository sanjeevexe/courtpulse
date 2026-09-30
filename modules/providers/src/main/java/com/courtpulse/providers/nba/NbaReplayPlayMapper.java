package com.courtpulse.providers.nba;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventType;
import com.courtpulse.domain.event.Score;
import com.courtpulse.domain.game.GamePeriods;
import com.courtpulse.providers.live.ProviderGame;
import com.courtpulse.providers.live.ProviderPlay;
import com.courtpulse.providers.live.ProviderTeam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps one replayed NBA action to canonical evidence. The action's position in provider order is
 * the canonical sequence (NBA action numbers skip values), the first action starts the game, later
 * period starts start periods, made shots and free throws score for the listed team and player,
 * the game-end action finishes the game, and everything else is a recorded play with its text.
 */
public final class NbaReplayPlayMapper {
    public static final String SOURCE = "nba-replay";
    public static final String PLAYER_PREFIX = "nba-player-";
    private static final Pattern CLOCK = Pattern.compile("^PT(\\d{1,2})M(\\d{1,2})(?:\\.(\\d{1,3})\\d*)?S$");

    private final ObjectMapper canonicalWriter;

    public NbaReplayPlayMapper(ObjectMapper objectMapper) {
        this.canonicalWriter = objectMapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public static String gameId(String providerGameId) {
        return "nba-replay-" + providerGameId;
    }

    public static String teamId(long nbaTeamId) {
        return "nba-team-" + nbaTeamId;
    }

    public static String playerId(long nbaPersonId) {
        return PLAYER_PREFIX + nbaPersonId;
    }

    public static ProviderTeam team(NbaReplayGame.Team team) {
        return new ProviderTeam(teamId(team.teamId()), Long.toString(team.teamId()), team.name(), team.tricode());
    }

    /** The raw evidence stored for an action: its fields as canonical, key-sorted JSON. */
    public String rawPayload(NbaAction action) {
        try {
            return canonicalWriter.writeValueAsString(canonicalWriter.convertValue(action, Object.class));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Replay action could not be serialized", exception);
        }
    }

    public ProviderPlay map(NbaReplayGame.TimedAction timed, ProviderGame game) {
        NbaAction action = timed.action();
        String raw = rawPayload(action);
        String hash = sha256(raw);
        String providerEventId = game.providerGameId() + ":" + action.actionNumber();
        try {
            return new ProviderPlay(providerEventId, timed.ordinal(), raw, hash, mapping(timed, game), null);
        } catch (Rejection rejection) {
            return new ProviderPlay(providerEventId, timed.ordinal(), raw, hash, null, rejection.code);
        }
    }

    private ProviderPlay.Mapping mapping(NbaReplayGame.TimedAction timed, ProviderGame game) {
        NbaAction action = timed.action();
        int period = action.period();
        if (period < 1 || period > GamePeriods.MAXIMUM_PERIOD) {
            throw new Rejection("invalid_period");
        }
        long clock = clockMillis(action.clock());
        if (clock > GamePeriods.maximumClockMillis(period)) {
            throw new Rejection("invalid_clock");
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(action.timeActual());
        } catch (DateTimeParseException exception) {
            throw new Rejection("invalid_time");
        }
        Score score = new Score(action.scoreHome(), action.scoreAway());
        String teamId = teamFor(action.teamId(), game);
        List<String> participants = action.personId() > 0 && action.personId() != action.teamId()
                ? List.of(playerId(action.personId())) : List.of();

        EventType type;
        int points = action.points();
        if (timed.ordinal() == 1) {
            if (score.home() != 0 || score.away() != 0) {
                throw new Rejection("start_with_score");
            }
            type = EventType.GAME_STARTED;
            points = 0;
        } else if (action.gameEnd()) {
            type = EventType.GAME_FINAL;
        } else if (action.periodStart()) {
            type = EventType.PERIOD_STARTED;
        } else if (points > 0) {
            if (teamId == null) {
                throw new Rejection("scoring_without_team");
            }
            if (participants.isEmpty()) {
                throw new Rejection("scoring_without_player");
            }
            type = points == 1 ? EventType.FREE_THROW_MADE : EventType.FIELD_GOAL_MADE;
        } else {
            type = EventType.PLAY_RECORDED;
        }
        if (type != EventType.FIELD_GOAL_MADE && type != EventType.FREE_THROW_MADE) {
            points = 0;
        }
        ProviderPlay.Mapping mapping = new ProviderPlay.Mapping(
                "nbar-" + game.providerGameId() + "-" + action.actionNumber(), game.gameId(), SOURCE, type,
                period, clock, occurredAt, teamId, participants, score, points, description(action.description()));
        try {
            mapping.toCanonical(game.providerGameId() + ":" + action.actionNumber(), timed.ordinal(), 1);
        } catch (IllegalArgumentException exception) {
            throw new Rejection("invalid_canonical_event");
        }
        return mapping;
    }

    static long clockMillis(String value) {
        Matcher match = CLOCK.matcher(value == null ? "" : value.strip());
        if (!match.matches()) {
            throw new Rejection("invalid_clock");
        }
        long seconds = Long.parseLong(match.group(2));
        if (seconds >= 60) {
            throw new Rejection("invalid_clock");
        }
        String fraction = match.group(3) == null ? "0" : (match.group(3) + "00").substring(0, 3);
        return Long.parseLong(match.group(1)) * 60_000 + seconds * 1_000 + Long.parseLong(fraction);
    }

    /** Display-only text: control characters and runs of whitespace removed, bounded length. */
    static String description(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > CanonicalEvent.MAXIMUM_DESCRIPTION_LENGTH) {
            int end = cleaned.offsetByCodePoints(0,
                    cleaned.codePointCount(0, CanonicalEvent.MAXIMUM_DESCRIPTION_LENGTH - 1));
            cleaned = cleaned.substring(0, end).strip() + "…";
        }
        return cleaned;
    }

    private static String teamFor(long nbaTeamId, ProviderGame game) {
        if (nbaTeamId <= 0) {
            return null;
        }
        String id = Long.toString(nbaTeamId);
        if (id.equals(game.homeTeam().providerTeamId())) {
            return game.homeTeam().teamId();
        }
        if (id.equals(game.awayTeam().providerTeamId())) {
            return game.awayTeam().teamId();
        }
        throw new Rejection("unknown_team");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }

    /** Fixed-vocabulary mapping failure; the code is stored, never the provider text. */
    private static final class Rejection extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        Rejection(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }
}
