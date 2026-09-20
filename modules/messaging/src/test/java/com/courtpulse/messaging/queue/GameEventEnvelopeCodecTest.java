package com.courtpulse.messaging.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameEventEnvelopeCodecTest {
    private final GameEventEnvelopeCodec codec = new GameEventEnvelopeCodec(
            JsonMapper.builder().addModule(new JavaTimeModule()).build());

    @Test
    void roundTripsVersionedEnvelope() {
        UUID outboxId = UUID.fromString("8b8deea9-ab01-420f-a562-5d30c2073170");
        GameEventEnvelope envelope = new GameEventEnvelope(
                outboxId.toString(), "CANONICAL_EVENT_READY", 1, "event-1", "game-1", 1,
                "fixture", "provider-1", 1, Instant.parse("2026-01-01T00:00:01Z"),
                outboxId, "canonical:event-1", "trace-1");

        assertEquals(envelope, codec.decode(codec.encode(envelope)));
    }

    @Test
    void rejectsMalformedAndUnsupportedSchema() {
        assertThrows(InvalidQueueMessageException.class, () -> codec.decode("not-json"));
        String unsupported = """
                {"messageId":"m","messageType":"CANONICAL_EVENT_READY","schemaVersion":2,
                 "eventId":"e","gameId":"g","sequence":1,"source":"s",
                 "providerEventId":"p","revision":1,"occurredAt":"2026-01-01T00:00:00Z",
                 "outboxId":"8b8deea9-ab01-420f-a562-5d30c2073170",
                 "deduplicationKey":"d","correlationId":null}
                """;
        assertThrows(InvalidQueueMessageException.class, () -> codec.decode(unsupported));
    }
}
