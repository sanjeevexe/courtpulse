package com.courtpulse.api.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrometheusScrapeControllerTest {
    @Test
    void scrapeIsUnavailableWithoutAConfiguredSecretAndRejectsWrongCredentials() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            @SuppressWarnings("unchecked")
            ObjectProvider<PrometheusMeterRegistry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(registry);
            assertEquals(404, new PrometheusScrapeController(provider, "").scrape(null)
                    .getStatusCode().value());
            var controller = new PrometheusScrapeController(provider, "local-test-only");
            assertEquals(401, controller.scrape(null).getStatusCode().value());
            assertEquals(401, controller.scrape("Basic !!!").getStatusCode().value());
            assertEquals(401, controller.scrape(basic("courtpulse:wrong"))
                    .getStatusCode().value());
            registry.counter("courtpulse.test.safe").increment();
            var accepted = controller.scrape(basic("courtpulse:local-test-only"));
            assertEquals(200, accepted.getStatusCode().value());
            assertTrue(accepted.getBody().contains("courtpulse_test_safe_total"));
            assertFalse(accepted.getBody().contains("local-test-only"));
        } finally {
            registry.close();
        }
    }

    private static String basic(String value) {
        return "Basic " + Base64.getEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }
}
