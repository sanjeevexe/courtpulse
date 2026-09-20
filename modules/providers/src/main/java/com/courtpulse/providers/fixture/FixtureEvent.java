package com.courtpulse.providers.fixture;

import com.courtpulse.domain.event.Score;
import java.time.Instant;
import java.util.List;

public record FixtureEvent(
        String eventId,
        int schemaVersion,
        String gameId,
        String source,
        String providerEventId,
        long sequence,
        int revision,
        String type,
        int period,
        long clockMillisRemaining,
        Instant occurredAt,
        String teamId,
        List<String> participantIds,
        Score scoreAfter,
        int points) {}
