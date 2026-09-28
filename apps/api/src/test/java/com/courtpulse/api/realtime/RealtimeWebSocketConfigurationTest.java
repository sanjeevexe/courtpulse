package com.courtpulse.api.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RealtimeWebSocketConfigurationTest {
    @Test
    void acceptsOnlyExplicitSecureOrigins() {
        assertEquals(List.of(), RealtimeWebSocketConfiguration.parseOrigins(""));
        assertEquals(List.of("https://d1234.cloudfront.net", "http://127.0.0.1:4173"),
                RealtimeWebSocketConfiguration.parseOrigins(" https://d1234.cloudfront.net , http://127.0.0.1:4173"));
        for (String unsafe : List.of("*", "https://*.cloudfront.net", "http://example.com",
                "https://example.com/path", "https://user@example.com", "not a uri")) {
            assertThrows(IllegalStateException.class,
                    () -> RealtimeWebSocketConfiguration.parseOrigins(unsafe), unsafe);
        }
    }
}
