package com.courtpulse.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.courtpulse.api.http.RateLimitFilter.EndpointClass;
import com.courtpulse.api.security.SecurityProblemWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class RateLimitFilterTest {
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void classifiesOnlyClientTrafficIntoFixedEndpointClasses() {
        assertEquals(EndpointClass.PUBLIC_READ, RateLimitFilter.classify("GET", "/api/v1/games/g1"));
        assertEquals(EndpointClass.OWNER_READ, RateLimitFilter.classify("GET", "/api/v1/me/rules"));
        assertEquals(EndpointClass.OWNER_WRITE, RateLimitFilter.classify("POST", "/api/v1/me/rules"));
        assertEquals(EndpointClass.OWNER_WRITE, RateLimitFilter.classify("DELETE", "/api/v1/me/followed-games/g1"));
        assertEquals(EndpointClass.OPERATIONS, RateLimitFilter.classify("GET", "/api/v1/operations/processing"));
        assertEquals(EndpointClass.REALTIME_HANDSHAKE, RateLimitFilter.classify("GET", "/ws/v1/games"));
        assertNull(RateLimitFilter.classify("GET", "/actuator/health/readiness"));
        assertNull(RateLimitFilter.classify("GET", "/internal/metrics"));
    }

    @Test
    void exhaustedBudgetReturnsProblemWithRetryAfterAndRefillsOverTime() throws Exception {
        RateLimitFilter filter = filter(3, 100);
        for (int request = 0; request < 3; request++) {
            assertEquals(200, call(filter, "GET", "/api/v1/games", "10.0.0.1").getStatus());
        }
        MockHttpServletResponse limited = call(filter, "GET", "/api/v1/games", "10.0.0.1");
        assertEquals(429, limited.getStatus());
        assertEquals("20", limited.getHeader("Retry-After"));
        assertTrue(limited.getContentAsString().contains("\"code\":\"rate_limited\""));
        assertEquals(200, call(filter, "GET", "/api/v1/games", "10.0.0.2").getStatus(), "other clients are unaffected");
        assertEquals(200, call(filter, "GET", "/actuator/health", "10.0.0.1").getStatus(), "probes are never limited");

        clock.addAndGet(20_000_000_000L);
        assertEquals(200, call(filter, "GET", "/api/v1/games", "10.0.0.1").getStatus());
        assertEquals(1.0, meters.get("courtpulse.http.rate.limited").tag("endpoint_class", "public_read").counter().count());
    }

    @Test
    void authenticatedCallersAreKeyedBySubjectNotAddress() throws Exception {
        RateLimitFilter filter = filter(2, 100);
        authenticate("user-a");
        call(filter, "POST", "/api/v1/me/rules", "10.0.0.1");
        call(filter, "POST", "/api/v1/me/rules", "10.0.0.2");
        assertEquals(429, call(filter, "POST", "/api/v1/me/rules", "10.0.0.3").getStatus(),
                "changing address does not reset a subject's budget");
        authenticate("user-b");
        assertEquals(200, call(filter, "POST", "/api/v1/me/rules", "10.0.0.1").getStatus());
    }

    @Test
    void trackedClientsStayBoundedUnderAKeyFlood() throws Exception {
        RateLimitFilter filter = filter(5, 50);
        for (int client = 0; client < 500; client++) {
            call(filter, "GET", "/api/v1/games", "10.0." + (client / 250) + "." + (client % 250));
        }
        assertTrue(filter.trackedClients() <= 50, "tracked=" + filter.trackedClients());
    }

    private RateLimitFilter filter(int perMinute, int maximumTrackedClients) {
        Map<EndpointClass, Integer> limits = new EnumMap<>(EndpointClass.class);
        for (EndpointClass endpointClass : EndpointClass.values()) {
            limits.put(endpointClass, perMinute);
        }
        return new RateLimitFilter(limits, new SecurityProblemWriter(new ObjectMapper()), meters,
                clock::get, maximumTrackedClients);
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String method, String path, String address)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(address);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static void authenticate(String subject) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(subject).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
