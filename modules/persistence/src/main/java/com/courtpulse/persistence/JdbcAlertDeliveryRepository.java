package com.courtpulse.persistence;

import com.courtpulse.observability.TraceContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Writes delivery intent in the same transaction as a newly created private alert. */
public final class JdbcAlertDeliveryRepository {
    private final JdbcClient jdbc;

    public JdbcAlertDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public NotificationSettings settings(String ownerSubject) {
        return jdbc.sql("""
                        SELECT COALESCE(preference.email_enabled, FALSE) AS email_enabled,
                               destination.address AS email_address
                        FROM application_users person
                        LEFT JOIN notification_preferences preference
                          ON preference.owner_subject = person.subject
                        LEFT JOIN notification_destinations destination
                          ON destination.owner_subject = person.subject
                         AND destination.channel = 'EMAIL'
                        WHERE person.subject = :owner
                        """)
                .param("owner", ownerSubject)
                .query((row, number) -> new NotificationSettings(
                        true, row.getBoolean("email_enabled"), row.getString("email_address")))
                .single();
    }

    public NotificationSettings saveSettings(
            String ownerSubject, boolean emailEnabled, String emailAddress, Instant now) {
        String normalized = emailAddress == null ? null : emailAddress.trim();
        if (emailEnabled && (normalized == null || normalized.isBlank())) {
            throw new IllegalArgumentException("An email address is required to enable email delivery");
        }
        if (normalized != null && !normalized.isBlank()
                && (normalized.length() > 254
                        || !normalized.matches("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$"))) {
            throw new IllegalArgumentException("Invalid email address");
        }
        var at = SqlTime.offset(now);
        NotificationSettings previous = settings(ownerSubject);
        boolean destinationChanged = normalized != null && !normalized.isBlank()
                && !normalized.equals(previous.emailAddress());
        if ((!emailEnabled && previous.emailEnabled()) || destinationChanged) {
            jdbc.sql("""
                            UPDATE alert_deliveries
                            SET status = 'CANCELLED', next_attempt_at = NULL,
                                last_error_code = 'preference_changed', updated_at = :now
                            WHERE owner_subject = :owner AND channel = 'EMAIL'
                              AND status IN ('PENDING', 'RETRY_SCHEDULED')
                            """)
                    .param("owner", ownerSubject).param("now", at).update();
            jdbc.sql("""
                            UPDATE delivery_outbox work
                            SET status = 'FAILED', last_error_code = 'preference_changed',
                                lease_owner = NULL, lease_until = NULL
                            FROM alert_deliveries delivery
                            WHERE work.delivery_id = delivery.id
                              AND delivery.owner_subject = :owner
                              AND delivery.status = 'CANCELLED'
                              AND work.status IN ('PENDING', 'RETRY_SCHEDULED', 'LEASED')
                            """)
                    .param("owner", ownerSubject).update();
        }
        jdbc.sql("""
                        INSERT INTO notification_preferences(owner_subject, email_enabled, updated_at)
                        VALUES (:owner, :enabled, :now)
                        ON CONFLICT (owner_subject) DO UPDATE
                        SET email_enabled = EXCLUDED.email_enabled, updated_at = EXCLUDED.updated_at
                        """)
                .params(Map.of("owner", ownerSubject, "enabled", emailEnabled, "now", at))
                .update();
        if (normalized != null && !normalized.isBlank()) {
            jdbc.sql("""
                            INSERT INTO notification_destinations(
                                id, owner_subject, channel, address, enabled, created_at, updated_at)
                            VALUES (:id, :owner, 'EMAIL', :address, TRUE, :now, :now)
                            ON CONFLICT (owner_subject, channel) DO UPDATE
                            SET address = EXCLUDED.address, enabled = TRUE,
                                updated_at = EXCLUDED.updated_at
                            """)
                    .params(Map.of("id", UUID.randomUUID(), "owner", ownerSubject,
                            "address", normalized, "now", at))
                    .update();
        }
        return settings(ownerSubject);
    }

    public List<DeliveryHistoryRecord> history(
            String ownerSubject, Instant beforeAt, UUID beforeId, int limit) {
        if (limit < 1 || limit > 101) {
            throw new IllegalArgumentException("internal page size must be between 1 and 101");
        }
        return jdbc.sql("""
                        SELECT delivery.id, delivery.alert_id, delivery.channel,
                               delivery.status, delivery.attempts, delivery.created_at,
                               delivery.delivered_at, delivery.next_attempt_at,
                               delivery.last_error_code
                        FROM alert_deliveries delivery
                        WHERE delivery.owner_subject = :owner
                        """ + (beforeAt == null ? "" :
                        " AND (delivery.created_at, delivery.id) < (:beforeAt, :beforeId)") + """
                        ORDER BY delivery.created_at DESC, delivery.id DESC
                        LIMIT :limit
                        """)
                .param("owner", ownerSubject).param("limit", limit)
                .param("beforeAt", beforeAt == null ? null : SqlTime.offset(beforeAt))
                .param("beforeId", beforeId)
                .query((row, number) -> new DeliveryHistoryRecord(
                        row.getObject("id", UUID.class),
                        row.getObject("alert_id", UUID.class),
                        row.getString("channel"),
                        row.getString("status"),
                        row.getInt("attempts"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getObject("delivered_at", OffsetDateTime.class) == null
                                ? null : row.getObject("delivered_at", OffsetDateTime.class).toInstant(),
                        row.getObject("next_attempt_at", OffsetDateTime.class) == null
                                ? null : row.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                        row.getString("last_error_code")))
                .list();
    }

    public boolean ownsDelivery(String ownerSubject, UUID deliveryId) {
        return jdbc.sql("SELECT count(*) FROM alert_deliveries WHERE id = :id AND owner_subject = :owner")
                .param("id", deliveryId).param("owner", ownerSubject)
                .query(Long.class).single() == 1L;
    }

    public List<DeliveryAttemptRecord> attempts(
            String ownerSubject, UUID deliveryId, Integer beforeAttempt, int limit) {
        return jdbc.sql("""
                        SELECT attempt.id, attempt.attempt_number, attempt.outcome,
                               attempt.error_code, attempt.completed_at
                        FROM delivery_attempts attempt
                        JOIN alert_deliveries delivery ON delivery.id = attempt.delivery_id
                        WHERE delivery.owner_subject = :owner AND delivery.id = :id
                        """ + (beforeAttempt == null ? "" :
                        " AND attempt.attempt_number < :beforeAttempt") + """
                        ORDER BY attempt.attempt_number DESC LIMIT :limit
                        """)
                .param("owner", ownerSubject).param("id", deliveryId)
                .param("beforeAttempt", beforeAttempt).param("limit", limit)
                .query((row, number) -> new DeliveryAttemptRecord(
                        row.getObject("id", UUID.class), row.getInt("attempt_number"),
                        row.getString("outcome"), row.getString("error_code"),
                        row.getObject("completed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public void createForAlert(UUID alertId, String ownerSubject, Instant now) {
        if (ownerSubject == null) {
            return;
        }
        var at = SqlTime.offset(now);
        jdbc.sql("""
                        INSERT INTO alert_deliveries (
                            id, alert_id, owner_subject, channel, status, attempts,
                            created_at, updated_at, delivered_at)
                        VALUES (:id, :alertId, :owner, 'IN_APP', 'DELIVERED', 0,
                            :now, :now, :now)
                        """)
                .params(Map.of("id", UUID.randomUUID(), "alertId", alertId,
                        "owner", ownerSubject, "now", at))
                .update();

        UUID emailDelivery = jdbc.sql("""
                        INSERT INTO alert_deliveries (
                            id, alert_id, owner_subject, channel, destination_id, address_snapshot,
                            status, attempts, next_attempt_at, created_at, updated_at)
                        SELECT :id, :alertId, :owner, 'EMAIL', destination.id, destination.address,
                               'PENDING', 0, :now, :now, :now
                        FROM notification_preferences preference
                        JOIN notification_destinations destination
                          ON destination.owner_subject = preference.owner_subject
                         AND destination.channel = 'EMAIL'
                        WHERE preference.owner_subject = :owner
                          AND preference.email_enabled AND destination.enabled
                        RETURNING id
                        """)
                .params(Map.of("id", UUID.randomUUID(), "alertId", alertId,
                        "owner", ownerSubject, "now", at))
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (emailDelivery != null) {
            jdbc.sql("""
                            INSERT INTO delivery_outbox (
                                id, delivery_id, status, attempts, next_attempt_at, created_at,
                                traceparent)
                            VALUES (:id, :deliveryId, 'PENDING', 0, :now, :now, :traceparent)
                            """)
                    .param("id", UUID.randomUUID()).param("deliveryId", emailDelivery)
                    .param("now", at).param("traceparent", TraceContext.currentTraceparent())
                    .update();
        }
    }
}
