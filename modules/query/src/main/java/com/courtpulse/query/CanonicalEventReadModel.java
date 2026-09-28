package com.courtpulse.query;

import java.time.Instant;
import java.util.List;

public record CanonicalEventReadModel(
        String eventId,
        int schemaVersion,
        String gameId,
        String source,
        String providerEventId,
        long sequence,
        int revision,
        String eventType,
        int period,
        long clockMillisRemaining,
        Instant occurredAt,
        String teamId,
        List<String> participantIds,
        ScoreReadModel scoreAfter,
        int points,
        String description) {
    public CanonicalEventReadModel {
        participantIds = List.copyOf(participantIds);
    }
}
