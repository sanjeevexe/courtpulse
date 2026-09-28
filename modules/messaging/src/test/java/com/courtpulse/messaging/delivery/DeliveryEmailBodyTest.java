package com.courtpulse.messaging.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.courtpulse.persistence.ClaimedEmailDelivery;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryEmailBodyTest {
    private static final ClaimedEmailDelivery DELIVERY = new ClaimedEmailDelivery(
            UUID.randomUUID(), "fan@example.test", "Ada Lane reached 30 points", "bdl-game-990001", true, 1);

    @Test
    void bodyNamesTheGameAndLinksToItOnlyWhenAPublicSiteIsConfigured() {
        String local = consumer(null).body(DELIVERY);
        assertEquals("""
                Ada Lane reached 30 points
                Game: bdl-game-990001

                You receive this because email alerts are enabled in your CourtPulse notification settings.""",
                local);
        assertFalse(local.contains("Mailpit"), "real recipients never see local-tooling text");
        String deployed = consumer("https://d1234.cloudfront.net").body(DELIVERY);
        assertEquals(true, deployed.contains("Open the game: https://d1234.cloudfront.net/games/bdl-game-990001"));
    }

    @Test
    void publicSiteMustBeABareHttpsOrigin() {
        assertThrows(IllegalArgumentException.class, () -> consumer("http://example.com"));
        assertThrows(IllegalArgumentException.class, () -> consumer("https://example.com/path"));
    }

    private static DeliveryQueueConsumer consumer(String publicBaseUrl) {
        return new DeliveryQueueConsumer(null, null, null, null, Clock.systemUTC(), "test", publicBaseUrl);
    }
}
