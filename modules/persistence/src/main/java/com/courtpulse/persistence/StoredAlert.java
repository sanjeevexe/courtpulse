package com.courtpulse.persistence;

import java.time.Instant;
import java.util.Map;

public record StoredAlert(
        String ruleId,
        String triggerKey,
        String gameId,
        String triggeringEventId,
        String title,
        Map<String, String> context,
        String status,
        Instant createdAt) {}
