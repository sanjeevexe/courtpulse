package com.courtpulse.providers.fixture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FixtureLoaderTest {
    private final FixtureLoader loader = new FixtureLoader();

    @Test
    void malformedFixtureFailsWithClearFieldError() {
        String malformed = """
                {
                  "fixtureSchemaVersion": 1,
                  "name": "Malformed",
                  "description": "Missing game id",
                  "provenance": "Synthetic",
                  "game": {"homeTeamId": "home", "awayTeamId": "away"},
                  "events": [{}]
                }
                """;

        FixtureFormatException exception =
                assertThrows(FixtureFormatException.class, () -> load(malformed));

        assertTrue(exception.getMessage().contains("game.gameId"), exception.getMessage());
    }

    @Test
    void unsupportedEventTypeFailsExplicitly() {
        String unsupported = """
                {
                  "fixtureSchemaVersion": 1,
                  "name": "Unsupported event",
                  "description": "Exercises quarantine policy",
                  "provenance": "Synthetic",
                  "game": {"gameId": "game-1", "homeTeamId": "home", "awayTeamId": "away"},
                  "events": [{
                    "eventId": "event-1", "schemaVersion": 1, "gameId": "game-1",
                    "source": "test", "providerEventId": "provider-1", "sequence": 1,
                    "revision": 1, "type": "UNKNOWN_EVENT", "period": 1,
                    "clockMillisRemaining": 720000, "occurredAt": "2026-01-01T00:00:00Z",
                    "teamId": null, "participantIds": [], "scoreAfter": {"home": 0, "away": 0},
                    "points": 0
                  }]
                }
                """;

        FixtureFormatException exception =
                assertThrows(FixtureFormatException.class, () -> load(unsupported));

        assertTrue(exception.getMessage().contains("Unsupported event type 'UNKNOWN_EVENT'"));
    }

    private LoadedFixture load(String json) {
        return loader.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
