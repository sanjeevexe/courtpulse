package com.courtpulse.persistence;

import com.courtpulse.domain.alert.Alert;
import com.courtpulse.domain.alert.ScoringRunFacts;
import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventFingerprint;
import com.courtpulse.domain.event.EventIdentity;
import com.courtpulse.domain.event.EventType;
import com.courtpulse.domain.event.Score;
import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.game.GameStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcGameProcessingRepository {
    public static final String CONSUMER_NAME = "durable-game-processor-v1";

    private final JdbcClient jdbc;
    private final PersistenceJson json;
    private final JdbcAlertDeliveryRepository deliveries;

    public JdbcGameProcessingRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.json = new PersistenceJson(objectMapper);
        this.deliveries = new JdbcAlertDeliveryRepository(jdbc);
    }

    public List<String> listEventIds(String gameId) {
        return jdbc.sql("""
                        SELECT event_id
                        FROM canonical_events
                        WHERE game_id = :gameId
                        ORDER BY sequence_number, revision
                        """)
                .param("gameId", gameId)
                .query(String.class)
                .list();
    }

    public CanonicalEvent findEvent(String eventId) {
        return jdbc.sql("""
                        SELECT event_id, schema_version, source, game_id, provider_event_id,
                               sequence_number, revision, event_type, period,
                               clock_millis_remaining, occurred_at, team_id,
                               participant_ids::TEXT AS participant_ids,
                               home_score, away_score, points
                        FROM canonical_events
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query(this::mapEvent)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown canonical event: " + eventId));
    }

    public GameState lockCheckpoint(String gameId) {
        return loadCheckpoint(gameId, true);
    }

    public GameState readCheckpoint(String gameId) {
        return loadCheckpoint(gameId, false);
    }

    private GameState loadCheckpoint(String gameId, boolean forUpdate) {
        String lockingClause = forUpdate ? " FOR UPDATE OF checkpoint" : "";
        CheckpointRow row = jdbc.sql("""
                                SELECT checkpoint.status, checkpoint.period,
                                       checkpoint.clock_millis_remaining,
                                       checkpoint.home_score, checkpoint.away_score,
                                       checkpoint.last_sequence,
                                       checkpoint.player_points::TEXT AS player_points,
                                       checkpoint.recent_event_ids::TEXT AS recent_event_ids,
                                       game.home_team_id, game.away_team_id
                                FROM game_checkpoints checkpoint
                                JOIN games game ON game.id = checkpoint.game_id
                                WHERE checkpoint.game_id = :gameId
                                """ + lockingClause)
                .param("gameId", gameId)
                .query((resultSet, rowNumber) -> new CheckpointRow(
                        GameStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("period"),
                        resultSet.getLong("clock_millis_remaining"),
                        resultSet.getInt("home_score"),
                        resultSet.getInt("away_score"),
                        resultSet.getLong("last_sequence"),
                        json.readPlayerPoints(resultSet.getString("player_points")),
                        json.readStringList(resultSet.getString("recent_event_ids")),
                        resultSet.getString("home_team_id"),
                        resultSet.getString("away_team_id")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("No checkpoint exists for game " + gameId));

        List<CanonicalEvent> recentEvents = row.recentEventIds().stream()
                .map(this::findEvent)
                .toList();
        List<CanonicalEvent> appliedEvents = jdbc.sql("""
                        SELECT event.event_id, event.schema_version, event.source, event.game_id,
                               event.provider_event_id, event.sequence_number, event.revision,
                               event.event_type, event.period, event.clock_millis_remaining,
                               event.occurred_at, event.team_id,
                               event.participant_ids::TEXT AS participant_ids,
                               event.home_score, event.away_score, event.points
                        FROM processed_events processed
                        JOIN canonical_events event ON event.event_id = processed.event_id
                        WHERE processed.consumer_name = :consumerName
                          AND processed.game_id = :gameId
                        ORDER BY event.sequence_number, event.revision
                        """)
                .params(Map.of("consumerName", CONSUMER_NAME, "gameId", gameId))
                .query(this::mapEvent)
                .list();
        Map<EventIdentity, String> fingerprints = new LinkedHashMap<>();
        for (CanonicalEvent appliedEvent : appliedEvents) {
            fingerprints.put(appliedEvent.identity(), EventFingerprint.sha256(appliedEvent));
        }

        return new GameState(
                gameId,
                row.homeTeamId(),
                row.awayTeamId(),
                row.status(),
                row.period(),
                row.clockMillisRemaining(),
                row.homeScore(),
                row.awayScore(),
                row.lastSequence(),
                row.playerPoints(),
                recentEvents,
                fingerprints);
    }

    public boolean isProcessed(CanonicalEvent event) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM processed_events
                        WHERE consumer_name = :consumerName
                          AND source = :source
                          AND game_id = :gameId
                          AND provider_event_id = :providerEventId
                          AND revision = :revision
                        """)
                .params(Map.of(
                        "consumerName", CONSUMER_NAME,
                        "source", event.source(),
                        "gameId", event.gameId(),
                        "providerEventId", event.providerEventId(),
                        "revision", event.revision()))
                .query(Long.class)
                .single() == 1L;
    }

    /** A historical queue redelivery is not another correction request. */
    public boolean isSuperseded(CanonicalEvent event) {
        return jdbc.sql("""
                        SELECT COALESCE(MAX(selected.revision), 0)
                        FROM processed_events processed
                        JOIN canonical_events selected ON selected.event_id = processed.event_id
                        WHERE processed.consumer_name = :consumer AND processed.game_id = :gameId
                          AND selected.sequence_number = :sequence
                        """)
                .param("consumer", CONSUMER_NAME).param("gameId", event.gameId())
                .param("sequence", event.sequence()).query(Integer.class).single() >= event.revision();
    }

    public boolean reconciliationActive(String gameId) {
        return jdbc.sql("""
                        SELECT count(*) FROM game_reconciliations
                        WHERE game_id = :gameId AND status IN ('PENDING', 'REBUILDING', 'BLOCKED')
                        """).param("gameId", gameId).query(Long.class).single() != 0;
    }

    /** Called with the checkpoint lock held, so a forward worker cannot race the request. */
    public void requestReconciliation(CanonicalEvent event, Instant now) {
        jdbc.sql("""
                        INSERT INTO game_reconciliations (
                            game_id, status, first_affected_sequence, correction_event_id,
                            requested_at, updated_at)
                        VALUES (:gameId, 'PENDING', :sequence, :eventId, :now, :now)
                        ON CONFLICT (game_id) DO UPDATE
                        SET status = 'PENDING',
                            first_affected_sequence = CASE
                                WHEN game_reconciliations.status IN ('COMPLETED', 'UNCHANGED')
                                    THEN :sequence
                                ELSE LEAST(game_reconciliations.first_affected_sequence, :sequence) END,
                            correction_event_id = :eventId,
                            requested_revision = game_reconciliations.requested_revision + 1,
                            requested_at = :now, updated_at = :now, completed_at = NULL,
                            last_error_code = NULL
                        WHERE game_reconciliations.correction_event_id IS DISTINCT FROM :eventId
                        """).param("gameId", event.gameId()).param("sequence", event.sequence())
                .param("eventId", event.eventId()).param("now", SqlTime.offset(now)).update();
    }

    public void saveCheckpoint(GameState state, String checksum, Instant now) {
        List<String> recentEventIds = state.recentEvents().stream()
                .map(CanonicalEvent::eventId)
                .toList();
        int rows = jdbc.sql("""
                        UPDATE game_checkpoints
                        SET state_version = state_version + 1,
                            status = :status,
                            period = :period,
                            clock_millis_remaining = :clock,
                            home_score = :homeScore,
                            away_score = :awayScore,
                            last_sequence = :lastSequence,
                            player_points = CAST(:playerPoints AS JSONB),
                            recent_event_ids = CAST(:recentEventIds AS JSONB),
                            state_checksum = :checksum,
                            updated_at = :now
                        WHERE game_id = :gameId
                        """)
                .params(Map.ofEntries(
                        Map.entry("status", state.status().name()),
                        Map.entry("period", state.period()),
                        Map.entry("clock", state.clockMillisRemaining()),
                        Map.entry("homeScore", state.homeScore()),
                        Map.entry("awayScore", state.awayScore()),
                        Map.entry("lastSequence", state.lastAppliedSequence()),
                        Map.entry("playerPoints", json.write(state.playerPoints())),
                        Map.entry("recentEventIds", json.write(recentEventIds)),
                        Map.entry("checksum", checksum),
                        Map.entry("now", SqlTime.offset(now)),
                        Map.entry("gameId", state.gameId())))
                .update();
        if (rows != 1) {
            throw new IllegalStateException("Checkpoint update failed for game " + state.gameId());
        }
        jdbc.sql("""
                        UPDATE games
                        SET status = :status, updated_at = :now
                        WHERE id = :gameId
                        """)
                .params(Map.of(
                        "status", state.status().name(),
                        "now", SqlTime.offset(now),
                        "gameId", state.gameId()))
                .update();
    }

    public boolean insertProcessed(CanonicalEvent event, Instant now) {
        int rows = jdbc.sql("""
                        INSERT INTO processed_events (
                            consumer_name, source, game_id, provider_event_id,
                            revision, event_id, processed_at)
                        VALUES (
                            :consumerName, :source, :gameId, :providerEventId,
                            :revision, :eventId, :now)
                        ON CONFLICT DO NOTHING
                        """)
                .params(Map.of(
                        "consumerName", CONSUMER_NAME,
                        "source", event.source(),
                        "gameId", event.gameId(),
                        "providerEventId", event.providerEventId(),
                        "revision", event.revision(),
                        "eventId", event.eventId(),
                        "now", SqlTime.offset(now)))
                .update();
        return rows == 1;
    }

    public long checkpointVersion(String gameId) {
        return jdbc.sql("SELECT state_version FROM game_checkpoints WHERE game_id = :gameId")
                .param("gameId", gameId).query(Long.class).single();
    }

    public boolean insertAlert(Alert alert, Instant now) {
        UUID alertId = UUID.randomUUID();
        int rows = jdbc.sql("""
                        INSERT INTO alert_instances (
                            id, rule_id, owner_subject, rule_type, game_id, trigger_key,
                            triggering_event_id, title, context, status, created_at)
                        VALUES (
                            :id, :ruleId, :ownerSubject, :ruleType, :gameId, :triggerKey,
                            :triggeringEventId, :title, CAST(:context AS JSONB), 'CREATED', :now)
                        ON CONFLICT (rule_id, trigger_key) DO NOTHING
                        """)
                .params(alertParameters(alert, alertId, now))
                .update();
        if (rows == 1) {
            deliveries.createForAlert(alertId, alert.ownerSubject(), now);
        }
        return rows == 1;
    }

    /** Advances one row on every scoring event, even when no scoring-run rule exists yet. */
    public ScoringRunFacts advanceScoringRun(CanonicalEvent event) {
        if (event.points() == 0) {
            return null;
        }
        PreviousRun previous = jdbc.sql("""
                        SELECT team_id, points, start_sequence
                        FROM game_scoring_runs
                        WHERE game_id = :gameId
                        FOR UPDATE
                        """)
                .param("gameId", event.gameId())
                .query((resultSet, rowNumber) -> new PreviousRun(
                        resultSet.getString("team_id"), resultSet.getInt("points"),
                        resultSet.getObject("start_sequence", Long.class)))
                .single();
        boolean sameTeam = event.teamId().equals(previous.teamId());
        int before = sameTeam ? previous.points() : 0;
        int after = Math.addExact(before, event.points());
        long start = sameTeam ? previous.startSequence() : event.sequence();
        jdbc.sql("""
                        UPDATE game_scoring_runs
                        SET team_id = :teamId, points = :points, start_sequence = :start
                        WHERE game_id = :gameId
                        """)
                .param("teamId", event.teamId())
                .param("points", after)
                .param("start", start)
                .param("gameId", event.gameId())
                .update();
        return new ScoringRunFacts(event.teamId(), before, after, start);
    }

    public List<StoredAlert> listAlerts(String gameId) {
        return jdbc.sql("""
                        SELECT rule_id, trigger_key, game_id, triggering_event_id,
                               title, context::TEXT AS context, status, created_at
                        FROM alert_instances
                        WHERE game_id = :gameId
                        ORDER BY created_at, trigger_key
                        """)
                .param("gameId", gameId)
                .query((resultSet, rowNumber) -> new StoredAlert(
                        resultSet.getString("rule_id"),
                        resultSet.getString("trigger_key"),
                        resultSet.getString("game_id"),
                        resultSet.getString("triggering_event_id"),
                        resultSet.getString("title"),
                        json.readStringMap(resultSet.getString("context")),
                        resultSet.getString("status"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private CanonicalEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CanonicalEvent(
                resultSet.getString("event_id"),
                resultSet.getInt("schema_version"),
                resultSet.getString("game_id"),
                resultSet.getString("source"),
                resultSet.getString("provider_event_id"),
                resultSet.getLong("sequence_number"),
                resultSet.getInt("revision"),
                EventType.valueOf(resultSet.getString("event_type")),
                resultSet.getInt("period"),
                resultSet.getLong("clock_millis_remaining"),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("team_id"),
                json.readStringList(resultSet.getString("participant_ids")),
                new Score(resultSet.getInt("home_score"), resultSet.getInt("away_score")),
                resultSet.getInt("points"));
    }

    private Map<String, Object> alertParameters(Alert alert, UUID alertId, Instant now) {
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("id", alertId);
        values.put("ruleId", alert.ruleId());
        values.put("ownerSubject", alert.ownerSubject());
        values.put("ruleType", alert.ruleType().name());
        values.put("gameId", alert.gameId());
        values.put("triggerKey", alert.triggerKey());
        values.put("triggeringEventId", alert.triggeringEventId());
        values.put("title", alert.title());
        values.put("context", json.write(alert.context()));
        values.put("now", SqlTime.offset(now));
        return values;
    }

    private record PreviousRun(String teamId, int points, Long startSequence) {}

    private record CheckpointRow(
            GameStatus status,
            int period,
            long clockMillisRemaining,
            int homeScore,
            int awayScore,
            long lastSequence,
            Map<String, Integer> playerPoints,
            List<String> recentEventIds,
            String homeTeamId,
            String awayTeamId) {}
}
