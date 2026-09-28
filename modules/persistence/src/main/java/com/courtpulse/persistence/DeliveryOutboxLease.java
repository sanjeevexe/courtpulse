package com.courtpulse.persistence;

import java.util.UUID;

public record DeliveryOutboxLease(UUID outboxId, UUID deliveryId, int attempt,
        String traceparent) {
    public DeliveryOutboxLease(UUID outboxId, UUID deliveryId, int attempt) {
        this(outboxId, deliveryId, attempt, null);
    }
}
