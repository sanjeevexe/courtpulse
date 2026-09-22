package com.courtpulse.persistence;

import com.courtpulse.domain.alert.AlertRule;
import com.courtpulse.domain.alert.CloseGameRule;
import com.courtpulse.domain.alert.PlayerMilestoneRule;
import com.courtpulse.domain.alert.RuleType;
import com.courtpulse.domain.alert.ScoringRunRule;
import com.courtpulse.domain.event.CanonicalEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Durable, owner-scoped rule storage and the processor's bounded rule lookup boundary. */
public final class JdbcAlertRuleRepository implements AlertRuleSource {
    public static final int MAX_CANDIDATES_PER_EVENT = 1_001;
    private final JdbcClient jdbc;
    private final PersistenceJson json;

    public JdbcAlertRuleRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.json = new PersistenceJson(objectMapper);
    }

    @Override
    public AlertRuleBatch relevantRules(CanonicalEvent event) {
        String playerId = event.participantIds().isEmpty() ? "" : event.participantIds().getFirst();
        String teamId = event.teamId() == null ? "" : event.teamId();
        Map<String, Object> parameters = Map.of(
                "gameId", event.gameId(),
                "playerId", playerId,
                "teamId", teamId,
                "scoringEvent", event.points() > 0,
                "period", event.period(),
                "clock", event.clockMillisRemaining(),
                "margin", Math.abs(event.scoreAfter().home() - event.scoreAfter().away()));
        String relevance = """
                game_id = :gameId AND (
                    (rule_type = 'CLOSE_GAME'
                        AND eligible_period = :period
                        AND maximum_clock_millis_remaining >= :clock
                        AND maximum_margin >= :margin)
                    OR (rule_type = 'PLAYER_POINTS' AND player_id = :playerId)
                    OR (rule_type = 'SCORING_RUN' AND :scoringEvent AND team_id = :teamId)
                )
                """;
        List<AlertRule> rules = jdbc.sql("""
                        SELECT id, owner_subject, rule_type, player_id, team_id,
                               points_threshold, maximum_margin, eligible_period,
                               maximum_clock_millis_remaining
                        FROM alert_rules
                        WHERE enabled AND
                        """ + relevance + " ORDER BY id LIMIT :candidateLimit FOR SHARE")
                .params(parameters)
                .param("candidateLimit", MAX_CANDIDATES_PER_EVENT + 1)
                .query((resultSet, rowNumber) -> mapDomainRule(resultSet))
                .list();
        if (rules.size() > MAX_CANDIDATES_PER_EVENT) {
            throw new IllegalStateException("Game alert-rule candidate limit exceeded");
        }
        long disabled = jdbc.sql("SELECT count(*) FROM alert_rules WHERE NOT enabled AND " + relevance)
                .params(parameters)
                .query(Long.class)
                .single();
        return new AlertRuleBatch(rules, disabled);
    }

    public void lockOwner(String ownerSubject) {
        requireOwner(ownerSubject);
        jdbc.sql("SELECT subject FROM application_users WHERE subject = :owner FOR UPDATE")
                .param("owner", ownerSubject)
                .query(String.class)
                .single();
    }

    public long countOwnedRules(String ownerSubject) {
        requireOwner(ownerSubject);
        return jdbc.sql("SELECT count(*) FROM alert_rules WHERE owner_subject = :owner")
                .param("owner", ownerSubject)
                .query(Long.class)
                .single();
    }

    public boolean lockGame(String gameId) {
        return jdbc.sql("SELECT id FROM games WHERE id = :gameId FOR UPDATE")
                .param("gameId", gameId)
                .query(String.class)
                .optional()
                .isPresent();
    }

    public long countOwnedGameRules(String gameId) {
        return jdbc.sql("""
                        SELECT count(*) FROM alert_rules
                        WHERE game_id = :gameId AND owner_subject IS NOT NULL
                        """)
                .param("gameId", gameId)
                .query(Long.class)
                .single();
    }

    public AlertRuleCreation create(
            String ownerSubject, String clientRequestId, NewAlertRule rule, Instant now) {
        requireOwner(ownerSubject);
        UUID id = UUID.randomUUID();
        Map<String, Object> values = values(id, ownerSubject, clientRequestId, rule, now);
        int inserted = jdbc.sql("""
                        INSERT INTO alert_rules (
                            id, owner_subject, game_id, rule_type, enabled,
                            player_id, team_id, points_threshold, maximum_margin,
                            eligible_period, maximum_clock_millis_remaining,
                            client_request_id, request_fingerprint, version, created_at, updated_at)
                        VALUES (
                            :id, :owner, :gameId, :type, :enabled,
                            :playerId, :teamId, :pointsThreshold, :maximumMargin,
                            :eligiblePeriod, :maximumClock, :requestId, :requestFingerprint,
                            1, :now, :now)
                        ON CONFLICT (owner_subject, client_request_id)
                            WHERE owner_subject IS NOT NULL AND client_request_id IS NOT NULL
                        DO NOTHING
                        """)
                .params(values)
                .update();
        StoredAlertRule stored = jdbc.sql("""
                        SELECT id, owner_subject, game_id, rule_type, enabled, player_id, team_id,
                               points_threshold, maximum_margin, eligible_period,
                               maximum_clock_millis_remaining, request_fingerprint,
                               version, created_at, updated_at
                        FROM alert_rules
                        WHERE owner_subject = :owner AND client_request_id = :requestId
                        """)
                .param("owner", ownerSubject)
                .param("requestId", clientRequestId)
                .query(this::mapStoredRule)
                .single();
        return new AlertRuleCreation(stored, inserted == 1);
    }

    public Optional<StoredAlertRule> findOwnedByRequestId(
            String ownerSubject, String clientRequestId) {
        requireOwner(ownerSubject);
        return jdbc.sql(selectStoredRule()
                        + " WHERE owner_subject = :owner AND client_request_id = :requestId")
                .param("owner", ownerSubject)
                .param("requestId", clientRequestId)
                .query(this::mapStoredRule)
                .optional();
    }

    public Optional<StoredAlertRule> findOwned(String ownerSubject, UUID id) {
        requireOwner(ownerSubject);
        return jdbc.sql(selectStoredRule() + " WHERE owner_subject = :owner AND id = :id")
                .param("owner", ownerSubject)
                .param("id", id)
                .query(this::mapStoredRule)
                .optional();
    }

    public List<StoredAlertRule> listOwned(
            String ownerSubject, Instant afterCreatedAt, UUID afterId, int fetchSize) {
        requireOwner(ownerSubject);
        String cursor = afterCreatedAt == null ? "" : """
                 AND (created_at < :afterCreatedAt
                      OR (created_at = :afterCreatedAt AND id > :afterId))
                """;
        var statement = jdbc.sql(selectStoredRule() + """
                 WHERE owner_subject = :owner
                """ + cursor + " ORDER BY created_at DESC, id ASC LIMIT :fetchSize")
                .param("owner", ownerSubject)
                .param("fetchSize", fetchSize);
        if (afterCreatedAt != null) {
            statement = statement
                    .param("afterCreatedAt", SqlTime.offset(afterCreatedAt))
                    .param("afterId", afterId);
        }
        return statement.query(this::mapStoredRule).list();
    }

    public Optional<StoredAlertRule> updateEnabled(
            String ownerSubject, UUID id, boolean enabled, long expectedVersion, Instant now) {
        requireOwner(ownerSubject);
        return jdbc.sql("""
                        UPDATE alert_rules
                        SET enabled = :enabled, version = version + 1, updated_at = :now
                        WHERE owner_subject = :owner AND id = :id AND version = :version
                        RETURNING id, owner_subject, game_id, rule_type, enabled, player_id, team_id,
                                  points_threshold, maximum_margin, eligible_period,
                                  maximum_clock_millis_remaining, request_fingerprint,
                                  version, created_at, updated_at
                        """)
                .param("enabled", enabled)
                .param("now", SqlTime.offset(now))
                .param("owner", ownerSubject)
                .param("id", id)
                .param("version", expectedVersion)
                .query(this::mapStoredRule)
                .optional();
    }

    public boolean deleteOwned(String ownerSubject, UUID id) {
        requireOwner(ownerSubject);
        return jdbc.sql("DELETE FROM alert_rules WHERE owner_subject = :owner AND id = :id")
                .param("owner", ownerSubject)
                .param("id", id)
                .update() == 1;
    }

    public List<OwnedAlertRecord> listOwnedAlerts(
            String ownerSubject, Instant afterCreatedAt, UUID afterId, int fetchSize) {
        requireOwner(ownerSubject);
        String cursor = afterCreatedAt == null ? "" : """
                 AND (created_at < :afterCreatedAt
                      OR (created_at = :afterCreatedAt AND id > :afterId))
                """;
        var statement = jdbc.sql("""
                        SELECT id, rule_id, rule_type, game_id, triggering_event_id,
                               title, context::TEXT AS context, status, created_at
                        FROM alert_instances
                        WHERE owner_subject = :owner
                        """ + cursor + " ORDER BY created_at DESC, id ASC LIMIT :fetchSize")
                .param("owner", ownerSubject)
                .param("fetchSize", fetchSize);
        if (afterCreatedAt != null) {
            statement = statement
                    .param("afterCreatedAt", SqlTime.offset(afterCreatedAt))
                    .param("afterId", afterId);
        }
        return statement.query((resultSet, rowNumber) -> new OwnedAlertRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("rule_id"),
                        RuleType.valueOf(resultSet.getString("rule_type")),
                        resultSet.getString("game_id"),
                        resultSet.getString("triggering_event_id"),
                        resultSet.getString("title"),
                        json.readStringMap(resultSet.getString("context")),
                        resultSet.getString("status"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public RuleOperationalSummary operationalSummary() {
        return jdbc.sql("""
                        SELECT
                          (SELECT count(*) FROM alert_rules WHERE owner_subject IS NOT NULL) AS owned_rules,
                          (SELECT count(*) FROM alert_rules
                             WHERE owner_subject IS NOT NULL AND enabled) AS enabled_owned_rules,
                          (SELECT count(*) FROM alert_rules
                             WHERE owner_subject IS NOT NULL AND NOT enabled) AS disabled_owned_rules,
                          (SELECT count(*) FROM alert_rules WHERE owner_subject IS NULL) AS system_rules,
                          (SELECT count(*) FROM alert_instances
                             WHERE owner_subject IS NOT NULL) AS private_alerts
                        """)
                .query((resultSet, rowNumber) -> new RuleOperationalSummary(
                        resultSet.getLong("owned_rules"),
                        resultSet.getLong("enabled_owned_rules"),
                        resultSet.getLong("disabled_owned_rules"),
                        resultSet.getLong("system_rules"),
                        resultSet.getLong("private_alerts")))
                .single();
    }

    private AlertRule mapDomainRule(ResultSet resultSet) throws SQLException {
        String owner = resultSet.getString("owner_subject");
        String id = owner == null
                ? "milestone-player-ace-10"
                : resultSet.getObject("id", UUID.class).toString();
        return switch (RuleType.valueOf(resultSet.getString("rule_type"))) {
            case PLAYER_POINTS -> new PlayerMilestoneRule(
                    id, owner, resultSet.getString("player_id"), resultSet.getInt("points_threshold"));
            case CLOSE_GAME -> new CloseGameRule(
                    id, owner, resultSet.getInt("maximum_margin"), resultSet.getInt("eligible_period"),
                    resultSet.getLong("maximum_clock_millis_remaining"));
            case SCORING_RUN -> new ScoringRunRule(
                    id, owner, resultSet.getString("team_id"), resultSet.getInt("points_threshold"));
        };
    }

    private StoredAlertRule mapStoredRule(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredAlertRule(
                resultSet.getObject("id", UUID.class), resultSet.getString("owner_subject"),
                resultSet.getString("game_id"), RuleType.valueOf(resultSet.getString("rule_type")),
                resultSet.getBoolean("enabled"), resultSet.getString("player_id"),
                resultSet.getString("team_id"), (Integer) resultSet.getObject("points_threshold"),
                (Integer) resultSet.getObject("maximum_margin"),
                (Integer) resultSet.getObject("eligible_period"),
                (Long) resultSet.getObject("maximum_clock_millis_remaining"),
                resultSet.getString("request_fingerprint"),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static String selectStoredRule() {
        return """
                SELECT id, owner_subject, game_id, rule_type, enabled, player_id, team_id,
                       points_threshold, maximum_margin, eligible_period,
                       maximum_clock_millis_remaining, request_fingerprint,
                       version, created_at, updated_at
                FROM alert_rules
                """;
    }

    private static Map<String, Object> values(
            UUID id, String owner, String requestId, NewAlertRule rule, Instant now) {
        Map<String, Object> values = new HashMap<>();
        values.put("id", id);
        values.put("owner", owner);
        values.put("requestId", requestId);
        values.put("requestFingerprint", rule.requestFingerprint());
        values.put("gameId", rule.gameId());
        values.put("type", rule.type().name());
        values.put("enabled", rule.enabled());
        values.put("playerId", rule.playerId());
        values.put("teamId", rule.teamId());
        values.put("pointsThreshold", rule.pointsThreshold());
        values.put("maximumMargin", rule.maximumMargin());
        values.put("eligiblePeriod", rule.eligiblePeriod());
        values.put("maximumClock", rule.maximumClockMillisRemaining());
        values.put("now", SqlTime.offset(now));
        return values;
    }

    private static void requireOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 255) {
            throw new IllegalArgumentException("Authenticated subject is invalid");
        }
    }
}
