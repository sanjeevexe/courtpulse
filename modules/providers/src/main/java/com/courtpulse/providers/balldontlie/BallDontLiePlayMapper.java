package com.courtpulse.providers.balldontlie;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventType;
import com.courtpulse.domain.event.Score;
import com.courtpulse.domain.game.GamePeriods;
import com.courtpulse.providers.live.ProviderGame;
import com.courtpulse.providers.live.ProviderPlay;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps one BALLDONTLIE v1 play to canonical evidence without looking at neighbouring plays, so a
 * refetched play always maps identically. The provider's {@code order} is the canonical sequence
 * and {@code <game_id>:<order>} the provider event identity; order 1 starts the game and
 * {@code End Game} finishes it. Scores are taken as reported and verified later by the reducer.
 */
public final class BallDontLiePlayMapper {
    public static final String SOURCE = "balldontlie";
    private static final int MAXIMUM_PARTICIPANTS = 10;
    private static final Pattern CLOCK = Pattern.compile("^(?:(\\d{1,2}):)?(\\d{1,2})(?:\\.(\\d{1,3}))?$");

    private final ObjectMapper canonicalWriter;

    public BallDontLiePlayMapper(ObjectMapper objectMapper) {
        this.canonicalWriter = objectMapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public static String gameId(String providerGameId) {
        return "bdl-game-" + providerGameId;
    }

    public static String teamId(String providerTeamId) {
        return "bdl-team-" + providerTeamId;
    }

    public static String playerId(String providerPlayerId) {
        return "bdl-player-" + providerPlayerId;
    }

    public ProviderPlay map(JsonNode play, ProviderGame game) {
        String raw = canonicalJson(play);
        String hash = sha256(raw);
        JsonNode orderNode = play.path("order");
        if (!orderNode.canConvertToLong() || orderNode.asLong() < 1 || !orderNode.isIntegralNumber()) {
            return new ProviderPlay(game.providerGameId() + ":malformed:" + hash.substring(0, 16),
                    0, raw, hash, null, "missing_order");
        }
        long order = orderNode.asLong();
        String providerEventId = game.providerGameId() + ":" + order;
        try {
            return new ProviderPlay(providerEventId, order, raw, hash, mapping(play, game, order), null);
        } catch (Rejection rejection) {
            return new ProviderPlay(providerEventId, order, raw, hash, null, rejection.code);
        }
    }

    private ProviderPlay.Mapping mapping(JsonNode play, ProviderGame game, long order) {
        int period = integer(play, "period", "invalid_period");
        if (period < 1 || period > GamePeriods.MAXIMUM_PERIOD) {
            throw new Rejection("invalid_period");
        }
        long clock = clockMillis(play.path("clock").asText(null));
        if (clock > GamePeriods.maximumClockMillis(period)) {
            throw new Rejection("invalid_clock");
        }
        Score score = new Score(nonNegative(play, "home_score"), nonNegative(play, "away_score"));
        Instant occurredAt = occurredAt(play.path("wallclock").asText(null), game.scheduledAt());
        String teamId = teamFor(play.path("team"), game);
        List<String> participants = participants(play.path("participants"));
        String description = description(play.path("text").asText(null));
        String type = play.path("type").asText("");
        boolean scoring = play.path("scoring_play").asBoolean(false);
        JsonNode scoreValue = play.path("score_value");

        EventType eventType;
        int points = 0;
        if (order == 1) {
            if (scoring || score.home() != 0 || score.away() != 0) {
                throw new Rejection("start_with_score");
            }
            eventType = EventType.GAME_STARTED;
        } else if ("End Game".equalsIgnoreCase(type.strip())) {
            eventType = EventType.GAME_FINAL;
        } else if (scoring) {
            if (!scoreValue.isIntegralNumber() || scoreValue.asInt() < 1 || scoreValue.asInt() > 3) {
                throw new Rejection("unsupported_score_value");
            }
            if (teamId == null) {
                throw new Rejection("scoring_without_team");
            }
            if (participants.isEmpty()) {
                throw new Rejection("scoring_without_player");
            }
            points = scoreValue.asInt();
            eventType = points == 1 ? EventType.FREE_THROW_MADE : EventType.FIELD_GOAL_MADE;
        } else {
            eventType = EventType.PLAY_RECORDED;
        }
        ProviderPlay.Mapping mapping = new ProviderPlay.Mapping(
                "bdl-" + game.providerGameId() + "-" + order, game.gameId(), SOURCE, eventType,
                period, clock, occurredAt, teamId, participants, score, points, description);
        try {
            mapping.toCanonical(game.providerGameId() + ":" + order, order, 1);
        } catch (IllegalArgumentException exception) {
            throw new Rejection("invalid_canonical_event");
        }
        return mapping;
    }

    public String canonicalJson(JsonNode node) {
        try {
            return canonicalWriter.writeValueAsString(canonicalWriter.convertValue(node, Object.class));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Provider JSON could not be canonicalized", exception);
        }
    }

    static long clockMillis(String value) {
        if (value == null) {
            throw new Rejection("invalid_clock");
        }
        Matcher match = CLOCK.matcher(value.strip());
        if (!match.matches()) {
            throw new Rejection("invalid_clock");
        }
        long minutes = match.group(1) == null ? 0 : Long.parseLong(match.group(1));
        long seconds = Long.parseLong(match.group(2));
        if (match.group(1) != null && seconds >= 60) {
            throw new Rejection("invalid_clock");
        }
        String fraction = match.group(3) == null ? "0" : (match.group(3) + "00").substring(0, 3);
        return minutes * 60_000 + seconds * 1_000 + Long.parseLong(fraction);
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

    private static String teamFor(JsonNode team, ProviderGame game) {
        if (team == null || team.isNull() || team.isMissingNode()) {
            return null;
        }
        String id = team.path("id").asText("");
        if (id.equals(game.homeTeam().providerTeamId())) {
            return game.homeTeam().teamId();
        }
        if (id.equals(game.awayTeam().providerTeamId())) {
            return game.awayTeam().teamId();
        }
        throw new Rejection("unknown_team");
    }

    private static List<String> participants(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAXIMUM_PARTICIPANTS) {
            throw new Rejection("invalid_participants");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode participant : node) {
            if (!participant.isIntegralNumber() || participant.asLong() < 1) {
                throw new Rejection("invalid_participants");
            }
            result.add(playerId(Long.toString(participant.asLong())));
        }
        return result;
    }

    private static Instant occurredAt(String wallclock, Instant fallback) {
        if (wallclock != null) {
            try {
                return Instant.parse(wallclock);
            } catch (DateTimeParseException exception) {
                throw new Rejection("invalid_timestamp");
            }
        }
        if (fallback == null) {
            throw new Rejection("invalid_timestamp");
        }
        return fallback;
    }

    private static int integer(JsonNode play, String field, String code) {
        JsonNode value = play.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new Rejection(code);
        }
        return value.asInt();
    }

    private static int nonNegative(JsonNode play, String field) {
        int value = integer(play, field, "invalid_score");
        if (value < 0) {
            throw new Rejection("invalid_score");
        }
        return value;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }

    /** Fixed-vocabulary mapping failure; the code is stored, never the provider text. */
    static final class Rejection extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;

        Rejection(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }
}
