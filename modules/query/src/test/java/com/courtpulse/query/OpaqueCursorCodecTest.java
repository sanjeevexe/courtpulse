package com.courtpulse.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpaqueCursorCodecTest {
    private final OpaqueCursorCodec codec = new OpaqueCursorCodec(JsonMapper.builder().build());

    @Test
    void roundTripsVersionedOpaqueKeys() {
        String cursor = codec.encode("events", "game-1", List.of("10", "2", "event-10"));

        assertEquals(
                List.of("10", "2", "event-10"),
                codec.decode(cursor, "events", "game-1", 3));
    }

    @Test
    void rejectsMalformedCursor() {
        assertThrows(InvalidCursorException.class,
                () -> codec.decode("not-base64!", "events", "game-1", 3));
    }

    @Test
    void rejectsCursorFromAnotherResourceOrScope() {
        String cursor = codec.encode("events", "game-1", List.of("10", "1", "event-10"));

        assertThrows(InvalidCursorException.class,
                () -> codec.decode(cursor, "alerts", "game-1", 3));
        assertThrows(InvalidCursorException.class,
                () -> codec.decode(cursor, "events", "game-2", 3));
    }
}
