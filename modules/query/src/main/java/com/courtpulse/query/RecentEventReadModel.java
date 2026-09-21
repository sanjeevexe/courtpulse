package com.courtpulse.query;

import java.time.Instant;

public record RecentEventReadModel(
        String eventId,
        long sequence,
        int revision,
        String eventType,
        Instant occurredAt,
        int homeScore,
        int awayScore) {}
