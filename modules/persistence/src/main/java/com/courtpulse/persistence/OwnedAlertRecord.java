package com.courtpulse.persistence;

import com.courtpulse.domain.alert.RuleType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OwnedAlertRecord(
        UUID id,
        String ruleId,
        RuleType ruleType,
        String gameId,
        String triggeringEventId,
        String title,
        Map<String, String> context,
        String status,
        Instant createdAt) {
    public OwnedAlertRecord {
        context = Map.copyOf(context);
    }
}
