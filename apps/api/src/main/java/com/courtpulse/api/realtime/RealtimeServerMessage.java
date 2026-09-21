package com.courtpulse.api.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeServerMessage(
        int schemaVersion,
        String messageType,
        String messageId,
        String gameId,
        Long stateVersion,
        Instant emittedAt,
        String correlationId,
        String eventId,
        String triggerKey,
        String code,
        String detail) {
    public static final int SCHEMA_VERSION = 1;
}
