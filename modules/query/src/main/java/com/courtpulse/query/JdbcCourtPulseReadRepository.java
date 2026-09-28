package com.courtpulse.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Purpose-built, bounded read-side SQL. No mutation or raw provider payload query lives here. */
public final class JdbcCourtPulseReadRepository {
    private static final TypeReference<Map<String, Integer>> PLAYER_POINTS = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final DataStatusPolicy dataStatusPolicy;
    private final Clock clock;

    public JdbcCourtPulseReadRepository(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            DataStatusPolicy dataStatusPolicy,
            Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataStatusPolicy = dataStatusPolicy;
        this.clock = clock;
    }

    public List<GameSummaryReadModel> listGames(
            String status,
            Instant afterUpdatedAt,
            String afterGameId,
            int fetchSize) {
        String statusPredicate = status == null ? "" : " AND checkpoint.status = :status\n";
        String cursorPredicate = afterUpdatedAt == null
                ? ""
                : " AND (checkpoint.updated_at < :afterUpdatedAt OR "
                        + "(checkpoint.updated_at = :afterUpdatedAt AND game.id > :afterGameId))\n";
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("fetchSize", fetchSize);
        if (status != null) {
            parameters.put("status", status);
        }
        if (afterUpdatedAt != null) {
            parameters.put("afterUpdatedAt", afterUpdatedAt.atOffset(java.time.ZoneOffset.UTC));
            parameters.put("afterGameId", afterGameId);
        }
        return jdbc.sql("""
                        SELECT game.id, game.source, game.home_team_id, game.away_team_id,
                               checkpoint.status, checkpoint.state_version, checkpoint.home_score,
                               checkpoint.away_score, checkpoint.period,
                               checkpoint.clock_millis_remaining, checkpoint.last_sequence,
                               checkpoint.updated_at, checkpoint.state_checksum,
                               GREATEST(checkpoint.updated_at, observation.last_success_at) AS fresh_at,
                               game.scheduled_at,
                               home.name AS home_team_name, home.abbreviation AS home_team_abbreviation,
                               away.name AS away_team_name, away.abbreviation AS away_team_abbreviation,
                               (EXISTS (
                                   SELECT 1 FROM outbox blocked
                                   WHERE blocked.destination = 'GAME_EVENTS'
                                     AND blocked.message_group_id = game.id
                                     AND blocked.status = 'FAILED'
                               ) OR EXISTS (
                                   SELECT 1 FROM game_reconciliations reconciliation
                                   WHERE reconciliation.game_id = game.id
                                     AND reconciliation.status = 'BLOCKED'
                               )) AS processing_blocked
                        FROM games game
                        JOIN game_checkpoints checkpoint ON checkpoint.game_id = game.id
                        LEFT JOIN provider_game_observations observation ON observation.game_id = game.id
                        LEFT JOIN teams home ON home.id = game.home_team_id
                        LEFT JOIN teams away ON away.id = game.away_team_id
                        WHERE 1 = 1
                        """ + statusPredicate + cursorPredicate + """
                        ORDER BY checkpoint.updated_at DESC, game.id ASC
                        LIMIT :fetchSize
                        """)
                .params(parameters)
                .query(this::mapGameSummary)
                .list();
    }

    public Optional<GameSnapshotReadModel> findGameSnapshot(String gameId) {
        Optional<SnapshotRow> row = jdbc.sql("""
                        SELECT game.id, game.source, game.home_team_id, game.away_team_id,
                               checkpoint.status, checkpoint.state_version, checkpoint.home_score,
                               checkpoint.away_score, checkpoint.period,
                               checkpoint.clock_millis_remaining, checkpoint.last_sequence,
                               checkpoint.player_points::TEXT AS player_points,
                               checkpoint.recent_event_ids::TEXT AS recent_event_ids,
                               checkpoint.updated_at, checkpoint.state_checksum,
                               GREATEST(checkpoint.updated_at, observation.last_success_at) AS fresh_at,
                               game.scheduled_at,
                               home.name AS home_team_name, home.abbreviation AS home_team_abbreviation,
                               away.name AS away_team_name, away.abbreviation AS away_team_abbreviation,
                               (EXISTS (
                                   SELECT 1 FROM outbox blocked
                                   WHERE blocked.destination = 'GAME_EVENTS'
                                     AND blocked.message_group_id = game.id
                                     AND blocked.status = 'FAILED'
                               ) OR EXISTS (
                                   SELECT 1 FROM game_reconciliations reconciliation
                                   WHERE reconciliation.game_id = game.id
                                     AND reconciliation.status = 'BLOCKED'
                               )) AS processing_blocked
                        FROM games game
                        JOIN game_checkpoints checkpoint ON checkpoint.game_id = game.id
                        LEFT JOIN provider_game_observations observation ON observation.game_id = game.id
                        LEFT JOIN teams home ON home.id = game.home_team_id
                        LEFT JOIN teams away ON away.id = game.away_team_id
                        WHERE game.id = :gameId
                        """)
                .param("gameId", gameId)
                .query((resultSet, rowNumber) -> new SnapshotRow(
                        resultSet.getString("id"),
                        resultSet.getString("source"),
                        resultSet.getString("home_team_id"),
                        resultSet.getString("away_team_id"),
                        resultSet.getString("status"),
                        resultSet.getLong("state_version"),
                        resultSet.getInt("home_score"),
                        resultSet.getInt("away_score"),
                        resultSet.getInt("period"),
                        resultSet.getLong("clock_millis_remaining"),
                        resultSet.getLong("last_sequence"),
                        read(resultSet.getString("player_points"), PLAYER_POINTS),
                        read(resultSet.getString("recent_event_ids"), STRING_LIST),
                        instant(resultSet, "updated_at"),
                        resultSet.getString("state_checksum"),
                        resultSet.getBoolean("processing_blocked"),
                        instant(resultSet, "fresh_at"),
                        teamLabels(resultSet),
                        nullableInstant(resultSet, "scheduled_at")))
                .optional();
        return row.map(value -> new GameSnapshotReadModel(
                value.gameId(),
                value.source(),
                value.homeTeamId(),
                value.awayTeamId(),
                value.status(),
                value.period(),
                value.clockMillisRemaining(),
                value.homeScore(),
                value.awayScore(),
                value.playerPoints(),
                value.stateVersion(),
                value.lastSequence(),
                value.stateChecksum(),
                recentEvents(value.recentEventIds()),
                value.updatedAt(),
                dataStatusPolicy.derive(
                        value.status(), value.freshAt(), value.processingBlocked()),
                value.teams(),
                value.scheduledAt(),
                playerNames(value.playerPoints().keySet())));
    }

    public boolean gameExists(String gameId) {
        return jdbc.sql("SELECT COUNT(*) FROM games WHERE id = :gameId")
                .param("gameId", gameId)
                .query(Long.class)
                .single() == 1L;
    }

    public List<CanonicalEventReadModel> listEvents(
            String gameId,
            Long afterSequence,
            Long cursorSequence,
            Integer cursorRevision,
            String cursorEventId,
            int fetchSize) {
        String predicate;
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("gameId", gameId);
        parameters.put("fetchSize", fetchSize);
        if (cursorSequence != null) {
            predicate = """
                     AND ROW(sequence_number, revision, event_id)
                         > ROW(:cursorSequence, :cursorRevision, :cursorEventId)
                    """;
            parameters.put("cursorSequence", cursorSequence);
            parameters.put("cursorRevision", cursorRevision);
            parameters.put("cursorEventId", cursorEventId);
        } else if (afterSequence != null) {
            predicate = " AND sequence_number > :afterSequence\n";
            parameters.put("afterSequence", afterSequence);
        } else {
            predicate = "";
        }
        return jdbc.sql("""
                        SELECT event_id, schema_version, game_id, source, provider_event_id,
                               sequence_number, revision, event_type, period,
                               clock_millis_remaining, occurred_at, team_id,
                               participant_ids::TEXT AS participant_ids,
                               home_score, away_score, points, description
                        FROM canonical_events
                        WHERE game_id = :gameId
                          AND (NOT EXISTS (
                              SELECT 1 FROM game_reconciliations reconciliation
                              WHERE reconciliation.game_id = :gameId
                          ) OR event_id IN (
                              SELECT processed.event_id FROM processed_events processed
                              WHERE processed.consumer_name = 'durable-game-processor-v1'
                                AND processed.game_id = :gameId
                          ))
                        """ + predicate + """
                        ORDER BY sequence_number, revision, event_id
                        LIMIT :fetchSize
                        """)
                .params(parameters)
                .query(this::mapEvent)
                .list();
    }

    public List<AlertReadModel> listAlerts(
            String gameId,
            Instant cursorCreatedAt,
            String cursorTriggerKey,
            int fetchSize) {
        String predicate = cursorCreatedAt == null
                ? ""
                : " AND (created_at < :cursorCreatedAt OR "
                        + "(created_at = :cursorCreatedAt AND trigger_key > :cursorTriggerKey))\n";
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("gameId", gameId);
        parameters.put("fetchSize", fetchSize);
        if (cursorCreatedAt != null) {
            parameters.put("cursorCreatedAt", cursorCreatedAt.atOffset(java.time.ZoneOffset.UTC));
            parameters.put("cursorTriggerKey", cursorTriggerKey);
        }
        return jdbc.sql("""
                        SELECT rule_id, trigger_key, game_id, triggering_event_id,
                               title, context::TEXT AS context, status, created_at
                        FROM alert_instances
                        WHERE game_id = :gameId
                          AND owner_subject IS NULL
                        """ + predicate + """
                        ORDER BY created_at DESC, trigger_key ASC
                        LIMIT :fetchSize
                        """)
                .params(parameters)
                .query((resultSet, rowNumber) -> new AlertReadModel(
                        resultSet.getString("rule_id"),
                        resultSet.getString("trigger_key"),
                        resultSet.getString("game_id"),
                        resultSet.getString("triggering_event_id"),
                        resultSet.getString("title"),
                        sanitizeContext(read(resultSet.getString("context"), STRING_MAP)),
                        resultSet.getString("status"),
                        instant(resultSet, "created_at")))
                .list();
    }

    public ProcessingHealthReadModel processingHealth() {
        return jdbc.sql("""
                        SELECT
                            COUNT(*) FILTER (WHERE destination = 'GAME_EVENTS' AND status = 'PENDING') AS pending,
                            COUNT(*) FILTER (WHERE destination = 'GAME_EVENTS' AND status = 'PUBLISHING') AS publishing,
                            COUNT(*) FILTER (WHERE destination = 'GAME_EVENTS' AND status = 'RETRY_SCHEDULED') AS retry_scheduled,
                            COUNT(*) FILTER (WHERE destination = 'GAME_EVENTS' AND status = 'SENT') AS sent,
                            COUNT(*) FILTER (WHERE destination = 'GAME_EVENTS' AND status = 'FAILED') AS failed,
                            COUNT(*) FILTER (WHERE destination = 'FUTURE_NOTIFICATIONS') AS deferred,
                            MIN(created_at) FILTER (
                                WHERE destination = 'GAME_EVENTS' AND (
                                    (status IN ('PENDING', 'RETRY_SCHEDULED') AND next_attempt_at <= :now)
                                    OR (status = 'PUBLISHING' AND lease_expires_at <= :now)
                                )) AS oldest_eligible,
                            COUNT(DISTINCT message_group_id) FILTER (
                                WHERE destination = 'GAME_EVENTS' AND status = 'FAILED') AS blocked_games,
                            (SELECT COUNT(*) FROM processed_events) AS processed_events,
                            (SELECT COUNT(*) FROM alert_instances) AS alerts
                        FROM outbox
                        """)
                .param("now", clock.instant().atOffset(java.time.ZoneOffset.UTC))
                .query((resultSet, rowNumber) -> {
                    OffsetDateTime oldest = resultSet.getObject("oldest_eligible", OffsetDateTime.class);
                    Long age = oldest == null
                            ? null
                            : Math.max(0, java.time.Duration.between(oldest.toInstant(), clock.instant()).toSeconds());
                    return new ProcessingHealthReadModel(
                            resultSet.getLong("pending"),
                            resultSet.getLong("publishing"),
                            resultSet.getLong("retry_scheduled"),
                            resultSet.getLong("sent"),
                            resultSet.getLong("failed"),
                            resultSet.getLong("deferred"),
                            age,
                            resultSet.getLong("blocked_games"),
                            resultSet.getLong("processed_events"),
                            resultSet.getLong("alerts"),
                            clock.instant());
                })
                .single();
    }

    private GameSummaryReadModel mapGameSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        Instant updatedAt = instant(resultSet, "updated_at");
        String status = resultSet.getString("status");
        return new GameSummaryReadModel(
                resultSet.getString("id"),
                resultSet.getString("source"),
                resultSet.getString("home_team_id"),
                resultSet.getString("away_team_id"),
                status,
                resultSet.getLong("state_version"),
                resultSet.getInt("home_score"),
                resultSet.getInt("away_score"),
                resultSet.getInt("period"),
                resultSet.getLong("clock_millis_remaining"),
                resultSet.getLong("last_sequence"),
                updatedAt,
                resultSet.getString("state_checksum"),
                dataStatusPolicy.derive(status, instant(resultSet, "fresh_at"),
                        resultSet.getBoolean("processing_blocked")),
                teamLabels(resultSet),
                nullableInstant(resultSet, "scheduled_at"));
    }

    private CanonicalEventReadModel mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CanonicalEventReadModel(
                resultSet.getString("event_id"),
                resultSet.getInt("schema_version"),
                resultSet.getString("game_id"),
                resultSet.getString("source"),
                resultSet.getString("provider_event_id"),
                resultSet.getLong("sequence_number"),
                resultSet.getInt("revision"),
                resultSet.getString("event_type"),
                resultSet.getInt("period"),
                resultSet.getLong("clock_millis_remaining"),
                instant(resultSet, "occurred_at"),
                resultSet.getString("team_id"),
                read(resultSet.getString("participant_ids"), STRING_LIST),
                new ScoreReadModel(resultSet.getInt("home_score"), resultSet.getInt("away_score")),
                resultSet.getInt("points"),
                resultSet.getString("description"));
    }

    private List<RecentEventReadModel> recentEvents(List<String> eventIds) {
        if (eventIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT event_id, sequence_number, revision, event_type, occurred_at,
                               home_score, away_score, description
                        FROM canonical_events
                        WHERE event_id IN (:eventIds)
                        ORDER BY sequence_number, revision, event_id
                        """)
                .param("eventIds", eventIds)
                .query((resultSet, rowNumber) -> new RecentEventReadModel(
                        resultSet.getString("event_id"),
                        resultSet.getLong("sequence_number"),
                        resultSet.getInt("revision"),
                        resultSet.getString("event_type"),
                        instant(resultSet, "occurred_at"),
                        resultSet.getInt("home_score"),
                        resultSet.getInt("away_score"),
                        resultSet.getString("description")))
                .list();
    }

    /** Names only for players already present in the durable state; bounded by that map. */
    private Map<String, String> playerNames(java.util.Set<String> playerIds) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.sql("SELECT id, display_name FROM players WHERE id IN (:ids) ORDER BY id")
                .param("ids", List.copyOf(playerIds))
                .query((resultSet, rowNumber) -> Map.entry(
                        resultSet.getString("id"), resultSet.getString("display_name")))
                .list()
                .forEach(entry -> names.put(entry.getKey(), entry.getValue()));
        return names;
    }

    private static TeamLabels teamLabels(ResultSet resultSet) throws SQLException {
        return new TeamLabels(
                resultSet.getString("home_team_name"),
                resultSet.getString("home_team_abbreviation"),
                resultSet.getString("away_team_name"),
                resultSet.getString("away_team_abbreviation"));
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Durable JSON state is invalid", exception);
        }
    }

    private static Map<String, String> sanitizeContext(Map<String, String> context) {
        Map<String, String> result = new LinkedHashMap<>();
        context.entrySet().stream().limit(32).forEach(entry -> result.put(
                bounded(entry.getKey(), 100), bounded(entry.getValue(), 500)));
        return result;
    }

    private static String bounded(String value, int maximum) {
        if (value == null) {
            return "";
        }
        String safe = value.replaceAll("[\\r\\n\\t]+", " ");
        return safe.substring(0, Math.min(safe.length(), maximum));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private record SnapshotRow(
            String gameId,
            String source,
            String homeTeamId,
            String awayTeamId,
            String status,
            long stateVersion,
            int homeScore,
            int awayScore,
            int period,
            long clockMillisRemaining,
            long lastSequence,
            Map<String, Integer> playerPoints,
            List<String> recentEventIds,
            Instant updatedAt,
            String stateChecksum,
            boolean processingBlocked,
            Instant freshAt,
            TeamLabels teams,
            Instant scheduledAt) {}
}
