package com.courtpulse.api.operations;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A separate scrape credential keeps metrics private even in unauthenticated local demos. */
@RestController
@Hidden
public final class PrometheusScrapeController {
    private final ObjectProvider<PrometheusMeterRegistry> registry;
    private final String password;

    public PrometheusScrapeController(ObjectProvider<PrometheusMeterRegistry> registry,
            @Value("${courtpulse.telemetry.scrape-password:}") String password) {
        this.registry = registry;
        this.password = password;
    }

    @GetMapping(value = "/internal/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> scrape(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (password.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        if (!valid(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"CourtPulse metrics\"")
                    .body("Unauthorized");
        }
        PrometheusMeterRegistry prometheus = registry.getIfAvailable();
        if (prometheus == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Metrics unavailable");
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain; version=0.0.4"))
                .body(prometheus.scrape());
    }

    private boolean valid(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6)),
                    StandardCharsets.UTF_8);
            String expected = "courtpulse:" + password;
            return MessageDigest.isEqual(decoded.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
