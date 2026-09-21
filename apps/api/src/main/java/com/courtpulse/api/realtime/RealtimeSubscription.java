package com.courtpulse.api.realtime;

public record RealtimeSubscription(
        int schemaVersion,
        String messageType,
        String gameId,
        Long lastStateVersion) {}
