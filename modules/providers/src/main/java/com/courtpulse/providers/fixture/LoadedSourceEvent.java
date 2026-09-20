package com.courtpulse.providers.fixture;

import com.courtpulse.domain.event.CanonicalEvent;
import java.util.Objects;

/** A canonical event paired with the stable source representation that produced it. */
public record LoadedSourceEvent(
        CanonicalEvent canonicalEvent,
        String rawPayload,
        String contentHash) {

    public LoadedSourceEvent {
        Objects.requireNonNull(canonicalEvent, "canonicalEvent is required");
        Objects.requireNonNull(rawPayload, "rawPayload is required");
        Objects.requireNonNull(contentHash, "contentHash is required");
        if (rawPayload.isBlank() || contentHash.isBlank()) {
            throw new IllegalArgumentException("Raw payload and content hash must not be blank");
        }
    }
}
