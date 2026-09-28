package com.courtpulse.providers.live;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventType;
import com.courtpulse.domain.event.Score;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One provider play: its stable raw evidence plus either a revision-independent canonical
 * mapping or a fixed rejection code. The ingestion boundary chooses the revision by comparing
 * content hashes with previously stored evidence.
 */
public record ProviderPlay(
        String providerEventId,
        long sequence,
        String rawPayload,
        String contentHash,
        Mapping mapping,
        String rejectionCode) {

    public ProviderPlay {
        Objects.requireNonNull(providerEventId, "providerEventId is required");
        Objects.requireNonNull(rawPayload, "rawPayload is required");
        Objects.requireNonNull(contentHash, "contentHash is required");
        if ((mapping == null) == (rejectionCode == null)) {
            throw new IllegalArgumentException("A play is either mapped or rejected");
        }
    }

    public boolean rejected() {
        return rejectionCode != null;
    }

    public CanonicalEvent atRevision(int revision) {
        if (mapping == null) {
            throw new IllegalStateException("A rejected play has no canonical event");
        }
        return mapping.toCanonical(providerEventId, sequence, revision);
    }

    /** Canonical fields that do not depend on the revision chosen for this evidence. */
    public record Mapping(
            String eventIdPrefix,
            String gameId,
            String source,
            EventType type,
            int period,
            long clockMillisRemaining,
            Instant occurredAt,
            String teamId,
            List<String> participantIds,
            Score scoreAfter,
            int points,
            String description) {
        public Mapping {
            participantIds = List.copyOf(participantIds);
        }

        public CanonicalEvent toCanonical(String providerEventId, long sequence, int revision) {
            return new CanonicalEvent(
                    eventIdPrefix + "-r" + revision,
                    CanonicalEvent.CURRENT_SCHEMA_VERSION,
                    gameId,
                    source,
                    providerEventId,
                    sequence,
                    revision,
                    type,
                    period,
                    clockMillisRemaining,
                    occurredAt,
                    teamId,
                    participantIds,
                    scoreAfter,
                    points,
                    description);
        }
    }
}
