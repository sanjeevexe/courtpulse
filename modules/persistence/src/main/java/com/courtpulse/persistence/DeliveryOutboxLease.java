package com.courtpulse.persistence;

import java.util.UUID;

public record DeliveryOutboxLease(UUID outboxId, UUID deliveryId, int attempt) {}
