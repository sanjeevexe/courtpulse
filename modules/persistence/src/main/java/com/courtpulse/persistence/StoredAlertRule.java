package com.courtpulse.persistence;

import com.courtpulse.domain.alert.RuleType;
import java.time.Instant;
import java.util.UUID;

public record StoredAlertRule(
        UUID id,
        String ownerSubject,
        String gameId,
        RuleType type,
        boolean enabled,
        String playerId,
        String teamId,
        Integer pointsThreshold,
        Integer maximumMargin,
        Integer eligiblePeriod,
        Long maximumClockMillisRemaining,
        String requestFingerprint,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
