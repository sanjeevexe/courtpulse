package com.courtpulse.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL boundary for short outbox claim and completion transactions. */
public final class JdbcOutboxPublicationRepository {
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcClient jdbc;

    public JdbcOutboxPublicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<LeasedOutboxRecord> claimGameEvents(
            String leaseOwner, int limit, Instant now, Duration leaseDuration) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        List<UUID> claimedIds = jdbc.sql("""
                        WITH candidates AS (
                            SELECT candidate.id
                            FROM outbox candidate
                            JOIN canonical_events candidate_event
                              ON candidate_event.event_id = candidate.aggregate_id
                            WHERE candidate.destination = 'GAME_EVENTS'
                              AND (
                                  (candidate.status IN ('PENDING', 'RETRY_SCHEDULED')
                                      AND candidate.next_attempt_at <= :now)
                                  OR
                                  (candidate.status = 'PUBLISHING'
                                      AND candidate.lease_expires_at <= :now)
                              )
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM outbox older
                                  JOIN canonical_events older_event
                                    ON older_event.event_id = older.aggregate_id
                                  WHERE older.destination = 'GAME_EVENTS'
                                    AND older.message_group_id = candidate.message_group_id
                                    AND older.id <> candidate.id
                                    AND ROW(
                                            older_event.sequence_number,
                                            older_event.revision,
                                            older.created_at,
                                            older.id)
                                        < ROW(
                                            candidate_event.sequence_number,
                                            candidate_event.revision,
                                            candidate.created_at,
                                            candidate.id)
                                    AND older.status <> 'SENT'
                              )
                            ORDER BY candidate_event.game_id,
                                     candidate_event.sequence_number,
                                     candidate_event.revision,
                                     candidate.created_at,
                                     candidate.id
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        UPDATE outbox claimed
                        SET status = 'PUBLISHING',
                            attempts = claimed.attempts + 1,
                            lease_owner = :leaseOwner,
                            lease_expires_at = :leaseExpiresAt,
                            last_error = NULL
                        FROM candidates
                        WHERE claimed.id = candidates.id
                        RETURNING claimed.id
                        """)
                .params(Map.of(
                        "now", SqlTime.offset(now),
                        "limit", limit,
                        "leaseOwner", leaseOwner,
                        "leaseExpiresAt", SqlTime.offset(now.plus(leaseDuration))))
                .query(UUID.class)
                .list();
        if (claimedIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT outbox.id, outbox.deduplication_key, outbox.aggregate_id,
                               outbox.message_group_id, outbox.attempts,
                               event.game_id, event.sequence_number, event.source,
                               event.provider_event_id, event.revision, event.occurred_at
                        FROM outbox
                        JOIN canonical_events event ON event.event_id = outbox.aggregate_id
                        WHERE outbox.id IN (:ids)
                        ORDER BY outbox.created_at, outbox.id
                        """)
                .param("ids", claimedIds)
                .query((resultSet, rowNumber) -> new LeasedOutboxRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("deduplication_key"),
                        resultSet.getString("aggregate_id"),
                        resultSet.getString("game_id"),
                        resultSet.getLong("sequence_number"),
                        resultSet.getString("source"),
                        resultSet.getString("provider_event_id"),
                        resultSet.getInt("revision"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        resultSet.getString("message_group_id"),
                        resultSet.getInt("attempts")))
                .list();
    }

    public boolean markSent(UUID id, String leaseOwner, Instant publishedAt) {
        return jdbc.sql("""
                        UPDATE outbox
                        SET status = 'SENT', published_at = :publishedAt,
                            lease_owner = NULL, lease_expires_at = NULL, last_error = NULL
                        WHERE id = :id
                          AND destination = 'GAME_EVENTS'
                          AND status = 'PUBLISHING'
                          AND lease_owner = :leaseOwner
                          AND lease_expires_at > :publishedAt
                        """)
                .params(Map.of(
                        "id", id,
                        "leaseOwner", leaseOwner,
                        "publishedAt", SqlTime.offset(publishedAt)))
                .update() == 1;
    }

    public List<RealtimeOutboxRecord> claimRealtime(
            String leaseOwner, int limit, Instant now, Duration leaseDuration) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        List<UUID> claimedIds = jdbc.sql("""
                        WITH candidates AS (
                            SELECT candidate.id
                            FROM outbox candidate
                            WHERE candidate.destination = 'FUTURE_NOTIFICATIONS'
                              AND candidate.event_type IN (
                                  'GAME_STATE_UPDATED', 'ALERT_CREATED', 'RESYNC_REQUIRED')
                              AND (
                                  (candidate.status IN ('PENDING', 'RETRY_SCHEDULED')
                                      AND candidate.next_attempt_at <= :now)
                                  OR
                                  (candidate.status = 'PUBLISHING'
                                      AND candidate.lease_expires_at <= :now)
                              )
                              AND (candidate.event_type = 'RESYNC_REQUIRED' OR NOT EXISTS (
                                  SELECT 1
                                  FROM outbox older
                                  WHERE older.destination = 'FUTURE_NOTIFICATIONS'
                                    AND older.message_group_id = candidate.message_group_id
                                    AND older.id <> candidate.id
                                    AND ROW(
                                            COALESCE(
                                                NULLIF(older.payload ->> 'stateVersion', '')::BIGINT,
                                                NULLIF(older.payload ->> 'sequence', '')::BIGINT,
                                                0),
                                            CASE WHEN older.event_type = 'GAME_STATE_UPDATED' THEN 0 ELSE 1 END,
                                            older.created_at,
                                            older.id)
                                        < ROW(
                                            COALESCE(
                                                NULLIF(candidate.payload ->> 'stateVersion', '')::BIGINT,
                                                NULLIF(candidate.payload ->> 'sequence', '')::BIGINT,
                                                0),
                                            CASE WHEN candidate.event_type = 'GAME_STATE_UPDATED' THEN 0 ELSE 1 END,
                                            candidate.created_at,
                                            candidate.id)
                                    AND older.status <> 'SENT'
                              ))
                            ORDER BY candidate.message_group_id,
                                     COALESCE(
                                         NULLIF(candidate.payload ->> 'stateVersion', '')::BIGINT,
                                         NULLIF(candidate.payload ->> 'sequence', '')::BIGINT,
                                         0),
                                     CASE WHEN candidate.event_type = 'GAME_STATE_UPDATED' THEN 0 ELSE 1 END,
                                     candidate.created_at,
                                     candidate.id
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        UPDATE outbox claimed
                        SET status = 'PUBLISHING',
                            attempts = claimed.attempts + 1,
                            lease_owner = :leaseOwner,
                            lease_expires_at = :leaseExpiresAt,
                            last_error = NULL
                        FROM candidates
                        WHERE claimed.id = candidates.id
                        RETURNING claimed.id
                        """)
                .params(Map.of(
                        "now", SqlTime.offset(now),
                        "limit", limit,
                        "leaseOwner", leaseOwner,
                        "leaseExpiresAt", SqlTime.offset(now.plus(leaseDuration))))
                .query(UUID.class)
                .list();
        if (claimedIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, event_type, message_group_id,
                               COALESCE(
                                   NULLIF(payload ->> 'stateVersion', '')::BIGINT,
                                   NULLIF(payload ->> 'sequence', '')::BIGINT,
                                   0) AS state_version,
                               payload ->> 'eventId' AS event_id,
                               payload ->> 'triggerKey' AS trigger_key,
                               attempts, created_at
                        FROM outbox
                        WHERE id IN (:ids)
                        ORDER BY created_at, id
                        """)
                .param("ids", claimedIds)
                .query((resultSet, rowNumber) -> new RealtimeOutboxRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getString("message_group_id"),
                        resultSet.getLong("state_version"),
                        resultSet.getString("event_id"),
                        resultSet.getString("trigger_key"),
                        resultSet.getInt("attempts"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public boolean markRealtimeSent(UUID id, String leaseOwner, Instant publishedAt) {
        return completeRealtime(id, leaseOwner, "SENT", publishedAt, publishedAt, null, publishedAt);
    }

    public boolean scheduleRealtimeRetry(
            UUID id, String leaseOwner, Instant completionTime, Instant nextAttemptAt, String error) {
        return completeRealtime(
                id, leaseOwner, "RETRY_SCHEDULED", completionTime, nextAttemptAt, error, null);
    }

    public boolean markRealtimeFailed(UUID id, String leaseOwner, Instant now, String error) {
        return completeRealtime(id, leaseOwner, "FAILED", now, now, error, null);
    }

    private boolean completeRealtime(
            UUID id,
            String leaseOwner,
            String status,
            Instant completionTime,
            Instant nextAttemptAt,
            String error,
            Instant publishedAt) {
        java.util.HashMap<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("id", id);
        parameters.put("leaseOwner", leaseOwner);
        parameters.put("status", status);
        parameters.put("completionTime", SqlTime.offset(completionTime));
        parameters.put("nextAttemptAt", SqlTime.offset(nextAttemptAt));
        parameters.put("lastError", error == null ? null : sanitize(error));
        parameters.put("publishedAt", publishedAt == null ? null : SqlTime.offset(publishedAt));
        return jdbc.sql("""
                        UPDATE outbox
                        SET status = :status, next_attempt_at = :nextAttemptAt,
                            published_at = COALESCE(:publishedAt, published_at),
                            lease_owner = NULL, lease_expires_at = NULL,
                            last_error = :lastError
                        WHERE id = :id
                          AND destination = 'FUTURE_NOTIFICATIONS'
                          AND status = 'PUBLISHING'
                          AND lease_owner = :leaseOwner
                          AND lease_expires_at > :completionTime
                        """)
                .params(parameters)
                .update() == 1;
    }

    public boolean scheduleRetry(
            UUID id,
            String leaseOwner,
            Instant completionTime,
            Instant nextAttemptAt,
            String error) {
        return completeFailure(
                id, leaseOwner, "RETRY_SCHEDULED", completionTime, nextAttemptAt, error);
    }

    public boolean markFailed(UUID id, String leaseOwner, Instant now, String error) {
        return completeFailure(id, leaseOwner, "FAILED", now, now, error);
    }

    private boolean completeFailure(
            UUID id,
            String leaseOwner,
            String status,
            Instant completionTime,
            Instant nextAttemptAt,
            String error) {
        return jdbc.sql("""
                        UPDATE outbox
                        SET status = :status, next_attempt_at = :nextAttemptAt,
                            lease_owner = NULL, lease_expires_at = NULL,
                            last_error = :lastError
                        WHERE id = :id
                          AND destination = 'GAME_EVENTS'
                          AND status = 'PUBLISHING'
                          AND lease_owner = :leaseOwner
                          AND lease_expires_at > :completionTime
                        """)
                .params(Map.of(
                        "status", status,
                        "nextAttemptAt", SqlTime.offset(nextAttemptAt),
                        "completionTime", SqlTime.offset(completionTime),
                        "lastError", sanitize(error),
                        "id", id,
                        "leaseOwner", leaseOwner))
                .update() == 1;
    }

    public boolean matchesGameEventOutbox(
            UUID id, String deduplicationKey, String eventId, String gameId) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM outbox
                        WHERE id = :id
                          AND destination = 'GAME_EVENTS'
                          AND event_type = 'CANONICAL_EVENT_READY'
                          AND deduplication_key = :deduplicationKey
                          AND aggregate_id = :eventId
                          AND message_group_id = :gameId
                        """)
                .params(Map.of(
                        "id", id,
                        "deduplicationKey", deduplicationKey,
                        "eventId", eventId,
                        "gameId", gameId))
                .query(Long.class)
                .single() == 1L;
    }

    public OutboxStatusCounts statusCounts() {
        return jdbc.sql("""
                        SELECT
                            COUNT(*) FILTER (WHERE destination = 'GAME_EVENTS' AND status = 'PENDING') AS pending,
                            COUNT(*) FILTER (WHERE destination = 'GAME_EVENTS' AND status = 'PUBLISHING') AS publishing,
                            COUNT(*) FILTER (WHERE destination = 'GAME_EVENTS' AND status = 'RETRY_SCHEDULED') AS retry_scheduled,
                            COUNT(*) FILTER (WHERE destination = 'GAME_EVENTS' AND status = 'SENT') AS sent,
                            COUNT(*) FILTER (WHERE destination = 'GAME_EVENTS' AND status = 'FAILED') AS failed,
                            COUNT(*) FILTER (WHERE destination = 'FUTURE_NOTIFICATIONS') AS deferred
                        FROM outbox
                        """)
                .query((resultSet, rowNumber) -> new OutboxStatusCounts(
                        resultSet.getLong("pending"),
                        resultSet.getLong("publishing"),
                        resultSet.getLong("retry_scheduled"),
                        resultSet.getLong("sent"),
                        resultSet.getLong("failed"),
                        resultSet.getLong("deferred")))
                .single();
    }

    private static String sanitize(String error) {
        String safe = error == null ? "unspecified publication failure" : error
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("(?i)(secret|token|password|credential)=[^ ]+", "$1=[redacted]")
                .trim();
        if (safe.isEmpty()) {
            safe = "unspecified publication failure";
        }
        return safe.substring(0, Math.min(safe.length(), MAX_ERROR_LENGTH));
    }
}
