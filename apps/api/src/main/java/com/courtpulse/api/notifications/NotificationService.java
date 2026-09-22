package com.courtpulse.api.notifications;

import com.courtpulse.persistence.DeliveryHistoryRecord;
import com.courtpulse.persistence.DeliveryAttemptRecord;
import com.courtpulse.persistence.JdbcAlertDeliveryRepository;
import com.courtpulse.persistence.JdbcUserOwnershipRepository;
import com.courtpulse.persistence.NotificationSettings;
import com.courtpulse.query.OpaqueCursorCodec;
import com.courtpulse.query.InvalidCursorException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

public final class NotificationService {
    private final JdbcAlertDeliveryRepository deliveries;
    private final JdbcUserOwnershipRepository users;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final OpaqueCursorCodec cursors;

    public NotificationService(
            JdbcAlertDeliveryRepository deliveries,
            JdbcUserOwnershipRepository users,
            TransactionTemplate transactions,
            Clock clock,
            OpaqueCursorCodec cursors) {
        this.deliveries = deliveries;
        this.users = users;
        this.transactions = transactions;
        this.clock = clock;
        this.cursors = cursors;
    }

    public NotificationSettings settings(String ownerSubject) {
        return transactions.execute(status -> {
            users.touchUser(ownerSubject, clock.instant());
            return deliveries.settings(ownerSubject);
        });
    }

    public NotificationSettings saveSettings(
            String ownerSubject, boolean emailEnabled, String emailAddress) {
        return transactions.execute(status -> {
            users.touchUser(ownerSubject, clock.instant());
            return deliveries.saveSettings(ownerSubject, emailEnabled, emailAddress, clock.instant());
        });
    }

    public DeliveryPage history(String ownerSubject, int limit, String cursor) {
        validateLimit(limit);
        Instant beforeAt = null;
        UUID beforeId = null;
        if (cursor != null) {
            List<String> keys = cursors.decode(cursor, "deliveries", ownerSubject, 2);
            try {
                beforeAt = Instant.parse(keys.get(0));
                beforeId = UUID.fromString(keys.get(1));
            } catch (IllegalArgumentException exception) {
                throw new InvalidCursorException("Cursor is malformed", exception);
            }
        }
        List<DeliveryHistoryRecord> fetched = deliveries.history(ownerSubject, beforeAt, beforeId, limit + 1);
        boolean more = fetched.size() > limit;
        List<DeliveryHistoryRecord> page = more ? fetched.subList(0, limit) : fetched;
        String next = more ? cursors.encode("deliveries", ownerSubject,
                List.of(page.getLast().createdAt().toString(), page.getLast().id().toString())) : null;
        return new DeliveryPage(page, next);
    }

    public DeliveryAttemptPage attempts(
            String ownerSubject, UUID deliveryId, int limit, String cursor) {
        validateLimit(limit);
        if (!deliveries.ownsDelivery(ownerSubject, deliveryId)) {
            throw new DeliveryNotFoundException();
        }
        Integer before = null;
        if (cursor != null) {
            List<String> keys = cursors.decode(
                    cursor, "delivery-attempts", ownerSubject + ":" + deliveryId, 1);
            try {
                before = Integer.parseInt(keys.getFirst());
                if (before < 1) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                throw new InvalidCursorException("Cursor is malformed", exception);
            }
        }
        List<DeliveryAttemptRecord> fetched = deliveries.attempts(ownerSubject, deliveryId, before, limit + 1);
        boolean more = fetched.size() > limit;
        List<DeliveryAttemptRecord> page = more ? fetched.subList(0, limit) : fetched;
        String next = more ? cursors.encode("delivery-attempts", ownerSubject + ":" + deliveryId,
                List.of(Integer.toString(page.getLast().attemptNumber()))) : null;
        return new DeliveryAttemptPage(page, next);
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
    }

    public record DeliveryPage(List<DeliveryHistoryRecord> items, String nextCursor) {}
    public record DeliveryAttemptPage(List<DeliveryAttemptRecord> items, String nextCursor) {}
}
