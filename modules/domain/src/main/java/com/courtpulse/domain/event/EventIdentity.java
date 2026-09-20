package com.courtpulse.domain.event;

import java.util.Objects;

/** Provider-scoped event identity. Delivery-specific IDs are intentionally excluded. */
public record EventIdentity(String source, String providerEventId, int revision) {
    public EventIdentity {
        source = requireText(source, "source");
        providerEventId = requireText(providerEventId, "providerEventId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be at least 1");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
