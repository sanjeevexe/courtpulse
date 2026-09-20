package com.courtpulse.messaging.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PublicationRetryPolicyTest {
    @Test
    void usesDeterministicBoundedExponentialBackoff() {
        PublicationRetryPolicy minimumJitter =
                new PublicationRetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(10), 5, () -> 0.0);
        PublicationRetryPolicy maximumJitter =
                new PublicationRetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(10), 5, () -> 1.0);

        assertEquals(Duration.ofSeconds(1), minimumJitter.afterFailure(1, true).delay());
        assertEquals(Duration.ofSeconds(2), minimumJitter.afterFailure(2, true).delay());
        assertEquals(Duration.ofSeconds(5), minimumJitter.afterFailure(4, true).delay());
        assertEquals(Duration.ofSeconds(10), maximumJitter.afterFailure(4, true).delay());
    }

    @Test
    void permanentAndExhaustedFailuresAreTerminal() {
        PublicationRetryPolicy policy =
                new PublicationRetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(8), 3, () -> 0.5);

        assertFalse(policy.afterFailure(1, false).retry());
        assertFalse(policy.afterFailure(3, true).retry());
        assertTrue(policy.afterFailure(2, true).retry());
    }
}
