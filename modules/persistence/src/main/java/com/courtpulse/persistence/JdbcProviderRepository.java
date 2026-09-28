package com.courtpulse.persistence;

import com.courtpulse.providers.live.ProviderClientStats;
import com.courtpulse.providers.live.ProviderGame;
import com.courtpulse.providers.live.ProviderPlayer;
import com.courtpulse.providers.live.ProviderTeam;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Provider metadata, freshness, client health, and data-quality evidence. */
public final class JdbcProviderRepository {
    private static final Set<String> INCIDENT_REASONS =
            Set.of("MAPPING_REJECTED", "REVERTED_CONTENT", "PLAY_REMOVED", "MALFORMED_RESPONSE");
    private static final int MAXIMUM_INCIDENT_PAYLOAD_BYTES = 16_384;

    private final JdbcClient jdbc;

    public JdbcProviderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertTeam(String source, ProviderTeam team, Instant now) {
        if (team.name() == null || team.abbreviation() == null) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO teams (id, source, provider_team_id, name, abbreviation, updated_at)
                        VALUES (:id, :source, :providerTeamId, :name, :abbreviation, :now)
                        ON CONFLICT (id) DO UPDATE
                        SET name = EXCLUDED.name, abbreviation = EXCLUDED.abbreviation,
                            updated_at = EXCLUDED.updated_at
                        WHERE teams.name IS DISTINCT FROM EXCLUDED.name
                           OR teams.abbreviation IS DISTINCT FROM EXCLUDED.abbreviation
                        """)
                .param("id", team.teamId()).param("source", source)
                .param("providerTeamId", team.providerTeamId()).param("name", team.name())
                .param("abbreviation", team.abbreviation()).param("now", SqlTime.offset(now))
                .update();
    }

    public void savePlayer(String source, ProviderPlayer player, Instant now) {
        jdbc.sql("""
                        INSERT INTO players (id, source, provider_player_id, display_name, updated_at)
                        VALUES (:id, :source, :providerPlayerId, :name, :now)
                        ON CONFLICT (id) DO UPDATE
                        SET display_name = EXCLUDED.display_name, updated_at = EXCLUDED.updated_at
                        WHERE players.display_name IS DISTINCT FROM EXCLUDED.display_name
                        """)
                .param("id", player.playerId()).param("source", source)
                .param("providerPlayerId", player.providerPlayerId())
                .param("name", player.displayName()).param("now", SqlTime.offset(now))
                .update();
    }

    /** Players referenced by recent canonical events whose display names are still unknown. */
    public List<String> unknownPlayerIds(String source, Instant since, int limit) {
        return jdbc.sql("""
                        SELECT DISTINCT participant.value
                        FROM canonical_events event
                        CROSS JOIN LATERAL jsonb_array_elements_text(event.participant_ids) participant
                        WHERE event.source = :source
                          AND event.created_at >= :since
                          AND NOT EXISTS (SELECT 1 FROM players player WHERE player.id = participant.value)
                        ORDER BY participant.value
                        LIMIT :limit
                        """)
                .param("source", source).param("since", SqlTime.offset(since)).param("limit", limit)
                .query(String.class).list();
    }

    /** Status and lifecycle only; freshness moves exclusively on a successful play poll. */
    public void observeGame(ProviderGame game, Instant now) {
        jdbc.sql("""
                        INSERT INTO provider_game_observations (
                            game_id, source, provider_game_id, provider_status, lifecycle, updated_at)
                        VALUES (:gameId, :source, :providerGameId, :status, :lifecycle, :now)
                        ON CONFLICT (game_id) DO UPDATE
                        SET provider_status = EXCLUDED.provider_status,
                            lifecycle = EXCLUDED.lifecycle, updated_at = EXCLUDED.updated_at
                        """)
                .params(gameParameters(game, now)).update();
    }

    public void recordPollSuccess(ProviderGame game, int observedPlays, boolean changed, Instant now) {
        Map<String, Object> parameters = gameParameters(game, now);
        parameters.put("plays", observedPlays);
        parameters.put("changed", changed);
        jdbc.sql("""
                        INSERT INTO provider_game_observations (
                            game_id, source, provider_game_id, provider_status, lifecycle,
                            last_attempt_at, last_success_at, consecutive_failures, observed_plays,
                            last_new_play_at, updated_at)
                        VALUES (:gameId, :source, :providerGameId, :status, :lifecycle,
                            :now, :now, 0, :plays, :now, :now)
                        ON CONFLICT (game_id) DO UPDATE
                        SET provider_status = EXCLUDED.provider_status,
                            lifecycle = EXCLUDED.lifecycle,
                            last_attempt_at = :now, last_success_at = :now,
                            last_error_code = NULL, consecutive_failures = 0,
                            observed_plays = :plays,
                            last_new_play_at = CASE
                                WHEN :changed OR provider_game_observations.last_new_play_at IS NULL
                                    THEN :now
                                ELSE provider_game_observations.last_new_play_at END,
                            updated_at = :now
                        """)
                .params(parameters).update();
    }

    public void recordPollFailure(ProviderGame game, String errorCode, Instant now) {
        Map<String, Object> parameters = gameParameters(game, now);
        parameters.put("error", errorCode);
        jdbc.sql("""
                        INSERT INTO provider_game_observations (
                            game_id, source, provider_game_id, provider_status, lifecycle,
                            last_attempt_at, last_error_code, consecutive_failures, updated_at)
                        VALUES (:gameId, :source, :providerGameId, :status, :lifecycle,
                            :now, :error, 1, :now)
                        ON CONFLICT (game_id) DO UPDATE
                        SET last_attempt_at = :now, last_error_code = :error,
                            consecutive_failures = provider_game_observations.consecutive_failures + 1,
                            updated_at = :now
                        """)
                .params(parameters).update();
    }

    public Instant lastNewPlayAt(String gameId) {
        return jdbc.sql("SELECT last_new_play_at FROM provider_game_observations WHERE game_id = :gameId")
                .param("gameId", gameId)
                .query((resultSet, rowNumber) -> {
                    var value = resultSet.getObject("last_new_play_at", java.time.OffsetDateTime.class);
                    return value == null ? null : value.toInstant();
                })
                .optional().orElse(null);
    }

    public boolean checkpointFinal(String gameId) {
        return jdbc.sql("SELECT status = 'FINAL' FROM game_checkpoints WHERE game_id = :gameId")
                .param("gameId", gameId).query(Boolean.class).optional().orElse(false);
    }

    /** A final game stays eligible for correction polling within the window after it finished. */
    public boolean dueForFinalRefetch(String gameId, Instant now, java.time.Duration window,
            java.time.Duration interval) {
        return jdbc.sql("""
                        SELECT checkpoint.updated_at >= :windowStart
                           AND (observation.last_success_at IS NULL
                                OR observation.last_success_at <= :intervalStart)
                        FROM game_checkpoints checkpoint
                        LEFT JOIN provider_game_observations observation
                            ON observation.game_id = checkpoint.game_id
                        WHERE checkpoint.game_id = :gameId AND checkpoint.status = 'FINAL'
                        """)
                .param("gameId", gameId)
                .param("windowStart", SqlTime.offset(now.minus(window)))
                .param("intervalStart", SqlTime.offset(now.minus(interval)))
                .query(Boolean.class).optional().orElse(false);
    }

    public boolean hasFinalEvent(String gameId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM canonical_events
                                       WHERE game_id = :gameId AND event_type = 'GAME_FINAL')
                        """)
                .param("gameId", gameId).query(Boolean.class).single();
    }

    /** Latest revision and every stored content hash for each provider identity of one game. */
    public Map<String, StoredRevisions> storedRevisions(String source, String gameId) {
        Map<String, StoredRevisions> result = new HashMap<>();
        jdbc.sql("""
                        SELECT provider_event_id, revision, content_hash
                        FROM raw_provider_payloads
                        WHERE source = :source AND game_id = :gameId
                        ORDER BY provider_event_id, revision
                        """)
                .param("source", source).param("gameId", gameId)
                .query((resultSet, rowNumber) -> new RevisionRow(
                        resultSet.getString("provider_event_id"), resultSet.getInt("revision"),
                        resultSet.getString("content_hash")))
                .list()
                .forEach(row -> result.merge(row.providerEventId(),
                        new StoredRevisions(row.revision(), row.contentHash(), Set.of(row.contentHash())),
                        (left, right) -> new StoredRevisions(
                                Math.max(left.latestRevision(), right.latestRevision()),
                                right.latestRevision() > left.latestRevision()
                                        ? right.latestHash() : left.latestHash(),
                                union(left.hashes(), right.hashes()))));
        return result;
    }

    /** Idempotent per identity and reason; the payload is bounded operator-only evidence. */
    public boolean recordIncident(String source, String gameId, String providerEventId, String reason,
            String detail, String payload, Instant now) {
        if (!INCIDENT_REASONS.contains(reason)) {
            throw new IllegalArgumentException("Unknown provider incident reason");
        }
        String boundedPayload = payload != null
                && payload.getBytes(StandardCharsets.UTF_8).length <= MAXIMUM_INCIDENT_PAYLOAD_BYTES
                ? payload : null;
        return jdbc.sql("""
                        INSERT INTO provider_data_incidents (
                            id, source, game_id, provider_event_id, reason, detail, payload, observed_at)
                        VALUES (:id, :source, :gameId, :providerEventId, :reason, :detail,
                            CAST(:payload AS JSONB), :now)
                        ON CONFLICT (source, game_id, provider_event_id, reason)
                            WHERE provider_event_id IS NOT NULL
                        DO NOTHING
                        """)
                .param("id", UUID.randomUUID()).param("source", source).param("gameId", gameId)
                .param("providerEventId", providerEventId).param("reason", reason)
                .param("detail", detail.length() > 200 ? detail.substring(0, 200) : detail)
                .param("payload", boundedPayload).param("now", SqlTime.offset(now))
                .update() == 1;
    }

    /** Adds this process's drained counters to the durable, cumulative client health row. */
    public void recordClientStats(String source, ProviderClientStats stats, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("source", source);
        parameters.put("circuit", stats.circuitState());
        parameters.put("attempt", stats.lastAttemptAt() == null ? null : SqlTime.offset(stats.lastAttemptAt()));
        parameters.put("success", stats.lastSuccessAt() == null ? null : SqlTime.offset(stats.lastSuccessAt()));
        parameters.put("error", stats.lastErrorCode());
        parameters.put("consecutive", stats.consecutiveFailures());
        parameters.put("requests", stats.requests());
        parameters.put("rateLimited", stats.rateLimited());
        parameters.put("failures", stats.failures());
        parameters.put("now", SqlTime.offset(now));
        jdbc.sql("""
                        INSERT INTO provider_health (
                            source, circuit_state, last_attempt_at, last_success_at, last_error_code,
                            consecutive_failures, requests_total, rate_limited_total, failures_total,
                            updated_at)
                        VALUES (:source, :circuit, :attempt, :success, :error, :consecutive,
                            :requests, :rateLimited, :failures, :now)
                        ON CONFLICT (source) DO UPDATE
                        SET circuit_state = EXCLUDED.circuit_state,
                            last_attempt_at = COALESCE(EXCLUDED.last_attempt_at, provider_health.last_attempt_at),
                            last_success_at = COALESCE(EXCLUDED.last_success_at, provider_health.last_success_at),
                            last_error_code = EXCLUDED.last_error_code,
                            consecutive_failures = EXCLUDED.consecutive_failures,
                            requests_total = provider_health.requests_total + EXCLUDED.requests_total,
                            rate_limited_total = provider_health.rate_limited_total + EXCLUDED.rate_limited_total,
                            failures_total = provider_health.failures_total + EXCLUDED.failures_total,
                            updated_at = EXCLUDED.updated_at
                        """)
                .params(parameters).update();
    }

    public ProviderOperations operations(Instant now) {
        return new ProviderOperations(jdbc.sql("""
                        SELECT health.source, health.circuit_state, health.requests_total,
                               health.rate_limited_total, health.failures_total,
                               health.consecutive_failures, health.last_error_code,
                               EXTRACT(EPOCH FROM (:now - health.last_success_at))::BIGINT AS success_age,
                               (SELECT count(*) FROM provider_game_observations observation
                                WHERE observation.source = health.source
                                  AND observation.lifecycle = 'SCHEDULED') AS scheduled_games,
                               (SELECT count(*) FROM provider_game_observations observation
                                WHERE observation.source = health.source
                                  AND observation.lifecycle = 'LIVE') AS live_games,
                               (SELECT count(*) FROM provider_game_observations observation
                                WHERE observation.source = health.source
                                  AND observation.lifecycle = 'FINAL') AS final_games,
                               (SELECT EXTRACT(EPOCH FROM (:now - min(COALESCE(
                                           observation.last_success_at, observation.updated_at))))::BIGINT
                                FROM provider_game_observations observation
                                WHERE observation.source = health.source
                                  AND observation.lifecycle = 'LIVE') AS stalest_live_age,
                               (SELECT count(*) FROM provider_data_incidents incident
                                WHERE incident.source = health.source) AS incidents
                        FROM provider_health health
                        ORDER BY health.source
                        """)
                .param("now", SqlTime.offset(now))
                .query((row, number) -> new ProviderOperations.Source(
                        row.getString("source"), row.getString("circuit_state"),
                        row.getLong("requests_total"), row.getLong("rate_limited_total"),
                        row.getLong("failures_total"), row.getInt("consecutive_failures"),
                        row.getString("last_error_code"), nonNegative((Long) row.getObject("success_age")),
                        row.getLong("scheduled_games"), row.getLong("live_games"),
                        row.getLong("final_games"), nonNegative((Long) row.getObject("stalest_live_age")),
                        row.getLong("incidents")))
                .list());
    }

    private static Long nonNegative(Long value) {
        return value == null ? null : Math.max(0, value);
    }

    private static Map<String, Object> gameParameters(ProviderGame game, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("gameId", game.gameId());
        parameters.put("source", game.source());
        parameters.put("providerGameId", game.providerGameId());
        parameters.put("status", game.providerStatus());
        parameters.put("lifecycle", game.lifecycle().name());
        parameters.put("now", SqlTime.offset(now));
        return parameters;
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        java.util.HashSet<String> result = new java.util.HashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }

    public record StoredRevisions(int latestRevision, String latestHash, Set<String> hashes) {}

    private record RevisionRow(String providerEventId, int revision, String contentHash) {}
}
