package com.courtpulse.query;

import java.time.Instant;
import java.util.Map;

public record AlertReadModel(
        String ruleId,
        String triggerKey,
        String gameId,
        String triggeringEventId,
        String title,
        Map<String, String> context,
        String status,
        Instant createdAt) {
    public AlertReadModel {
        context = Map.copyOf(context);
    }
}
