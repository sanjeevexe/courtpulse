package com.courtpulse.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Lease transitions and attempt history for local email delivery. All methods run in caller transactions. */
public final class JdbcDeliveryWorkRepository {
    private final JdbcClient jdbc;

    public JdbcDeliveryWorkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Aggregate-only operational view; never projects addresses or delivery identities. */
    public DeliveryOperations operations(Instant now) {
        return jdbc.sql("""
                        SELECT
                          (SELECT count(*) FROM alert_deliveries WHERE channel = 'EMAIL'
                             AND status IN ('PENDING', 'LEASED', 'RETRY_SCHEDULED')) AS backlog,
                          (SELECT coalesce(extract(epoch FROM (:now - min(created_at))), 0)
                             FROM alert_deliveries WHERE channel = 'EMAIL'
                             AND status IN ('PENDING', 'LEASED', 'RETRY_SCHEDULED')) AS oldest_age,
                          (SELECT count(*) FROM delivery_outbox WHERE status = 'SENT') AS publications,
                          (SELECT count(*) FROM delivery_attempts) AS attempts,
                          (SELECT count(*) FROM alert_deliveries WHERE channel = 'EMAIL'
                             AND status = 'DELIVERED') AS successes,
                          (SELECT count(*) FROM delivery_attempts
                             WHERE outcome = 'TRANSIENT_FAILURE') AS retries,
                          (SELECT count(*) FROM alert_deliveries WHERE channel = 'EMAIL'
                             AND status = 'FAILED') AS terminal_failures,
                          (SELECT count(*) FROM delivery_attempts
                             WHERE outcome = 'UNKNOWN_ACCEPTANCE') AS lease_recoveries,
                          (SELECT dlq_depth FROM delivery_worker_observation
                             WHERE singleton = TRUE) AS dlq_depth,
                          (SELECT observed_at FROM delivery_worker_observation
                             WHERE singleton = TRUE) AS dlq_observed_at
                        """)
                .param("now", SqlTime.offset(now))
                .query((row, number) -> new DeliveryOperations(
                        row.getLong("backlog"), Math.max(0, row.getDouble("oldest_age")),
                        row.getLong("publications"), row.getLong("attempts"),
                        row.getLong("successes"), row.getLong("retries"),
                        row.getLong("terminal_failures"), row.getLong("lease_recoveries"),
                        row.getLong("dlq_depth"),
                        row.getObject("dlq_observed_at", java.time.OffsetDateTime.class) == null
                                ? null : row.getObject("dlq_observed_at", java.time.OffsetDateTime.class)
                                        .toInstant()))
                .single();
    }

    public void observeDlqDepth(long depth, Instant now) {
        if (depth < 0) throw new IllegalArgumentException("DLQ depth cannot be negative");
        jdbc.sql("""
                        INSERT INTO delivery_worker_observation(singleton, dlq_depth, observed_at)
                        VALUES (TRUE, :depth, :now)
                        ON CONFLICT (singleton) DO UPDATE
                        SET dlq_depth = EXCLUDED.dlq_depth, observed_at = EXCLUDED.observed_at
                        """)
                .param("depth", depth).param("now", SqlTime.offset(now)).update();
    }

    /** Recovers ambiguous SMTP leases and terminally fails exhausted publisher leases. */
    public int recoverExpired(Instant now) {
        var at = SqlTime.offset(now);
        List<ExpiredLease> expired = jdbc.sql("""
                        SELECT delivery.id, delivery.attempts, alert.status AS alert_status
                        FROM alert_deliveries delivery
                        JOIN alert_instances alert ON alert.id = delivery.alert_id
                        WHERE delivery.channel = 'EMAIL' AND delivery.status = 'LEASED'
                          AND delivery.lease_until <= :now
                        ORDER BY delivery.lease_until, delivery.id LIMIT 100
                        FOR UPDATE OF delivery SKIP LOCKED
                        """)
                .param("now", at)
                .query((row, number) -> new ExpiredLease(
                        row.getObject("id", UUID.class), row.getInt("attempts"),
                        "CORRECTED".equals(row.getString("alert_status"))))
                .list();
        for (ExpiredLease lease : expired) {
            boolean exhausted = lease.attempts() >= 5;
            boolean cancelled = lease.corrected();
            jdbc.sql("""
                            UPDATE alert_deliveries
                            SET status = :status, lease_owner = NULL, lease_until = NULL,
                                next_attempt_at = :next, last_error_code = :errorCode,
                                updated_at = :now
                            WHERE id = :id AND status = 'LEASED'
                            """)
                    .param("status", cancelled ? "CANCELLED" : exhausted ? "FAILED" : "RETRY_SCHEDULED")
                    .param("next", cancelled || exhausted ? null : at)
                    .param("errorCode", cancelled ? "alert_corrected" : "lease_expired")
                    .param("now", at).param("id", lease.id()).update();
            jdbc.sql("""
                            INSERT INTO delivery_attempts(
                                id, delivery_id, attempt_number, outcome, error_code,
                                started_at, completed_at)
                            VALUES (:id, :deliveryId, :attempt, 'UNKNOWN_ACCEPTANCE',
                                    'lease_expired', :now, :now)
                            ON CONFLICT (delivery_id, attempt_number) DO NOTHING
                            """)
                    .param("id", UUID.randomUUID()).param("deliveryId", lease.id())
                    .param("attempt", lease.attempts()).param("now", at).update();
            jdbc.sql("""
                            UPDATE delivery_outbox
                            SET status = :status, next_attempt_at = :now,
                                lease_owner = NULL, lease_until = NULL,
                                last_error_code = :errorCode
                            WHERE delivery_id = :id AND status <> 'FAILED'
                            """)
                    .param("status", cancelled || exhausted ? "FAILED" : "RETRY_SCHEDULED")
                    .param("errorCode", cancelled ? "alert_corrected" : "lease_expired")
                    .param("now", at).param("id", lease.id()).update();
        }
        int exhaustedPublications = jdbc.sql("""
                        WITH failed AS (
                            UPDATE delivery_outbox SET status = 'FAILED', lease_owner = NULL,
                                lease_until = NULL, last_error_code = 'publication_lease_exhausted'
                            WHERE status = 'LEASED' AND lease_until <= :now AND attempts >= 5
                            RETURNING delivery_id
                        )
                        UPDATE alert_deliveries delivery
                        SET status = 'FAILED', next_attempt_at = NULL,
                            last_error_code = 'publication_lease_exhausted', updated_at = :now
                        FROM failed WHERE delivery.id = failed.delivery_id
                          AND delivery.status IN ('PENDING', 'RETRY_SCHEDULED')
                        """)
                .param("now", at).update();
        jdbc.sql("""
                        UPDATE delivery_outbox
                        SET status = 'RETRY_SCHEDULED', next_attempt_at = :now,
                            lease_owner = NULL, lease_until = NULL,
                            last_error_code = 'publication_lease_expired'
                        WHERE status = 'LEASED' AND lease_until <= :now AND attempts < 5
                        """)
                .param("now", at).update();
        return expired.size() + exhaustedPublications;
    }

    public List<DeliveryOutboxLease> claimOutbox(
            String owner, int limit, Instant now, Duration leaseDuration) {
        return jdbc.sql("""
                        WITH picked AS (
                            SELECT id FROM delivery_outbox
                            WHERE ((status IN ('PENDING', 'RETRY_SCHEDULED')
                                       AND next_attempt_at <= :now)
                                OR (status = 'LEASED' AND lease_until <= :now))
                              AND attempts < 5
                            ORDER BY next_attempt_at, id
                            LIMIT :limit FOR UPDATE SKIP LOCKED
                        )
                        UPDATE delivery_outbox work
                        SET status = 'LEASED', attempts = work.attempts + 1,
                            lease_owner = :owner, lease_until = :leaseUntil
                        FROM picked WHERE work.id = picked.id
                        RETURNING work.id, work.delivery_id, work.attempts
                        """)
                .params(Map.of("now", SqlTime.offset(now), "limit", limit,
                        "owner", owner, "leaseUntil", SqlTime.offset(now.plus(leaseDuration))))
                .query((row, number) -> new DeliveryOutboxLease(
                        row.getObject("id", UUID.class), row.getObject("delivery_id", UUID.class),
                        row.getInt("attempts")))
                .list();
    }

    public boolean markPublished(UUID outboxId, String owner, String providerId, Instant now) {
        return jdbc.sql("""
                        UPDATE delivery_outbox SET status = 'SENT', lease_owner = NULL,
                            lease_until = NULL, provider_message_id = :providerId,
                            published_at = :now
                        WHERE id = :id AND status = 'LEASED' AND lease_owner = :owner
                        """)
                .param("id", outboxId).param("owner", owner)
                .param("providerId", providerId).param("now", SqlTime.offset(now))
                .update() == 1;
    }

    public boolean publicationFailed(
            UUID outboxId, String owner, String errorCode, Instant now, int attempt, boolean retry) {
        long baseSeconds = Math.min(300, 5L << Math.max(0, attempt - 1));
        Instant next = retry ? now.plusSeconds(baseSeconds)
                .plusMillis(ThreadLocalRandom.current().nextLong(baseSeconds * 250 + 1)) : now;
        UUID deliveryId = jdbc.sql("""
                        UPDATE delivery_outbox
                        SET status = :status, lease_owner = NULL, lease_until = NULL,
                            next_attempt_at = :next, last_error_code = :errorCode
                        WHERE id = :id AND status = 'LEASED' AND lease_owner = :owner
                        RETURNING delivery_id
                        """)
                .param("status", retry ? "RETRY_SCHEDULED" : "FAILED")
                .param("next", SqlTime.offset(next))
                .param("errorCode", errorCode)
                .param("id", outboxId).param("owner", owner)
                .query(UUID.class).optional().orElse(null);
        if (deliveryId == null) return false;
        if (!retry) {
            jdbc.sql("""
                            UPDATE alert_deliveries SET status = 'FAILED', next_attempt_at = NULL,
                                last_error_code = :errorCode, updated_at = :now
                            WHERE id = :id AND status IN ('PENDING', 'RETRY_SCHEDULED')
                            """)
                    .param("errorCode", errorCode).param("now", SqlTime.offset(now))
                    .param("id", deliveryId).update();
        }
        return true;
    }

    public ClaimedEmailDelivery claimDelivery(
            UUID deliveryId, String owner, Instant now, Duration leaseDuration) {
        UUID claimed = jdbc.sql("""
                        UPDATE alert_deliveries delivery
                        SET status = 'LEASED', attempts = attempts + 1,
                            lease_owner = :owner, lease_until = :leaseUntil, updated_at = :now
                        FROM alert_instances alert
                        WHERE delivery.id = :id AND delivery.alert_id = alert.id
                          AND alert.status = 'CREATED' AND delivery.channel = 'EMAIL'
                          AND delivery.attempts < 5
                          AND delivery.status IN ('PENDING', 'RETRY_SCHEDULED')
                          AND delivery.next_attempt_at <= :now
                        RETURNING delivery.id
                        """)
                .params(Map.of("id", deliveryId, "owner", owner,
                        "now", SqlTime.offset(now),
                        "leaseUntil", SqlTime.offset(now.plus(leaseDuration))))
                .query(UUID.class).optional().orElse(null);
        if (claimed == null) {
            return null;
        }
        return jdbc.sql("""
                        SELECT delivery.id, delivery.address_snapshot AS address, alert.title, alert.game_id,
                               preference.email_enabled AND destination.enabled AS opted_in,
                               delivery.attempts
                        FROM alert_deliveries delivery
                        JOIN alert_instances alert ON alert.id = delivery.alert_id
                        JOIN notification_destinations destination ON destination.id = delivery.destination_id
                        JOIN notification_preferences preference
                          ON preference.owner_subject = delivery.owner_subject
                        WHERE delivery.id = :id
                        """)
                .param("id", deliveryId)
                .query((row, number) -> new ClaimedEmailDelivery(
                        row.getObject("id", UUID.class), row.getString("address"),
                        row.getString("title"), row.getString("game_id"),
                        row.getBoolean("opted_in"), row.getInt("attempts")))
                .single();
    }

    public String deliveryStatus(UUID deliveryId) {
        return jdbc.sql("SELECT status FROM alert_deliveries WHERE id = :id")
                .param("id", deliveryId).query(String.class).optional().orElse(null);
    }

    public boolean finishDelivery(
            ClaimedEmailDelivery delivery, String owner, String outcome,
            String errorCode, String providerId, Instant now, Instant nextAttempt) {
        String status = switch (outcome) {
            case "SENT" -> "DELIVERED";
            case "TRANSIENT_FAILURE" -> "RETRY_SCHEDULED";
            case "PERMANENT_FAILURE" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> throw new IllegalArgumentException("Unknown delivery outcome");
        };
        String alertStatus = jdbc.sql("""
                        SELECT alert.status FROM alert_instances alert
                        JOIN alert_deliveries delivery ON delivery.alert_id = alert.id
                        WHERE delivery.id = :id FOR SHARE OF alert
                        """).param("id", delivery.id()).query(String.class).single();
        boolean corrected = "CORRECTED".equals(alertStatus) && !"SENT".equals(outcome);
        if (corrected) status = "CANCELLED";
        Instant effectiveNext = corrected ? null : nextAttempt;
        int updated = jdbc.sql("""
                        UPDATE alert_deliveries SET status = :status, lease_owner = NULL,
                            lease_until = NULL, next_attempt_at = :next,
                            provider_message_id = :providerId, last_error_code = :errorCode,
                            delivered_at = CASE WHEN :status = 'DELIVERED' THEN :now ELSE delivered_at END,
                            updated_at = :now
                        WHERE id = :id AND status = 'LEASED' AND lease_owner = :owner
                        """)
                .param("status", status).param("next", effectiveNext == null ? null : SqlTime.offset(effectiveNext))
                .param("providerId", providerId).param("errorCode", corrected ? "alert_corrected" : errorCode)
                .param("now", SqlTime.offset(now)).param("id", delivery.id()).param("owner", owner)
                .update();
        if (updated != 1) {
            return false;
        }
        jdbc.sql("""
                        INSERT INTO delivery_attempts (
                            id, delivery_id, attempt_number, outcome, error_code,
                            provider_message_id, started_at, completed_at)
                        VALUES (:id, :deliveryId, :attempt, :outcome, :errorCode,
                                :providerId, :now, :now)
                        """)
                .param("id", UUID.randomUUID()).param("deliveryId", delivery.id())
                .param("attempt", delivery.attempt()).param("outcome", outcome)
                .param("errorCode", errorCode).param("providerId", providerId)
                .param("now", SqlTime.offset(now)).update();
        if (corrected) {
            jdbc.sql("""
                            UPDATE delivery_outbox SET status = 'FAILED', lease_owner = NULL,
                                lease_until = NULL, last_error_code = 'alert_corrected'
                            WHERE delivery_id = :deliveryId AND status <> 'FAILED'
                            """).param("deliveryId", delivery.id()).update();
        } else if (nextAttempt != null) {
            jdbc.sql("""
                            UPDATE delivery_outbox
                            SET status = 'RETRY_SCHEDULED', next_attempt_at = :next,
                                lease_owner = NULL, lease_until = NULL,
                                last_error_code = :errorCode
                            WHERE delivery_id = :deliveryId
                            """)
                    .param("next", SqlTime.offset(nextAttempt))
                    .param("errorCode", errorCode)
                    .param("deliveryId", delivery.id()).update();
        } else {
            // A redelivered SQS message can settle while an expired publication was
            // rescheduled. Do not keep republishing a terminal delivery identity.
            jdbc.sql("""
                            UPDATE delivery_outbox
                            SET status = 'SENT', lease_owner = NULL, lease_until = NULL
                            WHERE delivery_id = :deliveryId AND status <> 'FAILED'
                            """)
                    .param("deliveryId", delivery.id()).update();
        }
        return true;
    }

    private record ExpiredLease(UUID id, int attempts, boolean corrected) {}
}
