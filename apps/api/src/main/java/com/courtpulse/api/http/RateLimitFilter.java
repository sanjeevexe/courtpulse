package com.courtpulse.api.http;

import com.courtpulse.api.security.SecurityProblemWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client token buckets by endpoint class (OWASP API4, unrestricted resource consumption).
 * Authenticated callers are keyed by JWT subject, which a client cannot forge; anonymous callers
 * by remote address, which is best effort behind proxies (behind a CDN, add edge rate rules).
 * Buckets live in memory per API instance and are bounded; the rules quota and pagination caps
 * remain the durable limits.
 */
public final class RateLimitFilter extends OncePerRequestFilter {
    public enum EndpointClass { PUBLIC_READ, OWNER_READ, OWNER_WRITE, OPERATIONS, REALTIME_HANDSHAKE }

    private static final long IDLE_NANOS = 10L * 60 * 1_000_000_000L;

    private final Map<EndpointClass, Integer> perMinute;
    private final SecurityProblemWriter problems;
    private final LongSupplier nanoTime;
    private final int maximumTrackedClients;
    private final Map<EndpointClass, Counter> rejected = new EnumMap<>(EndpointClass.class);
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(Map<EndpointClass, Integer> perMinute, SecurityProblemWriter problems,
            MeterRegistry registry, LongSupplier nanoTime, int maximumTrackedClients) {
        for (EndpointClass endpointClass : EndpointClass.values()) {
            Integer limit = perMinute.get(endpointClass);
            if (limit == null || limit < 1) {
                throw new IllegalArgumentException("A positive per-minute limit is required for " + endpointClass);
            }
            rejected.put(endpointClass, Counter.builder("courtpulse.http.rate.limited")
                    .tag("endpoint_class", endpointClass.name().toLowerCase(java.util.Locale.ROOT))
                    .register(registry));
        }
        this.perMinute = Map.copyOf(perMinute);
        this.problems = problems;
        this.nanoTime = nanoTime;
        this.maximumTrackedClients = maximumTrackedClients;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        EndpointClass endpointClass = classify(request.getMethod(), request.getRequestURI());
        if (endpointClass == null) {
            chain.doFilter(request, response);
            return;
        }
        long now = nanoTime.getAsLong();
        if (buckets.size() >= maximumTrackedClients) {
            evictIdle(now);
        }
        int limit = perMinute.get(endpointClass);
        Bucket bucket = buckets.computeIfAbsent(endpointClass + "|" + client(request, endpointClass),
                key -> new Bucket(limit, now));
        long retryAfterSeconds = bucket.tryConsume(now);
        if (retryAfterSeconds == 0) {
            chain.doFilter(request, response);
            return;
        }
        rejected.get(endpointClass).increment();
        problems.tooManyRequests(request, response, retryAfterSeconds);
    }

    static EndpointClass classify(String method, String path) {
        if ("/ws/v1/games".equals(path)) {
            return EndpointClass.REALTIME_HANDSHAKE;
        }
        if (path.startsWith("/api/v1/operations/")) {
            return EndpointClass.OPERATIONS;
        }
        if ("/api/v1/me".equals(path) || path.startsWith("/api/v1/me/")) {
            return "GET".equals(method) || "HEAD".equals(method) ? EndpointClass.OWNER_READ : EndpointClass.OWNER_WRITE;
        }
        if (path.startsWith("/api/v1/")) {
            return EndpointClass.PUBLIC_READ;
        }
        return null; // health probes, the private scrape route, and API docs are not client traffic
    }

    private static String client(HttpServletRequest request, EndpointClass endpointClass) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (endpointClass != EndpointClass.REALTIME_HANDSHAKE && authentication instanceof JwtAuthenticationToken jwt) {
            return "sub:" + jwt.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private void evictIdle(long now) {
        buckets.entrySet().removeIf(entry -> entry.getValue().idleSince(now) > IDLE_NANOS);
        if (buckets.size() >= maximumTrackedClients) {
            buckets.clear(); // bounded memory wins over perfect fairness during a key flood
        }
    }

    int trackedClients() {
        return buckets.size();
    }

    /** Continuous refill at limit per minute, with a burst of one minute's allowance. */
    private static final class Bucket {
        private final double capacity;
        private final double tokensPerNano;
        private double tokens;
        private long updatedAt;

        Bucket(int perMinute, long now) {
            this.capacity = perMinute;
            this.tokensPerNano = perMinute / 60_000_000_000.0;
            this.tokens = perMinute;
            this.updatedAt = now;
        }

        synchronized long tryConsume(long now) {
            tokens = Math.min(capacity, tokens + Math.max(0, now - updatedAt) * tokensPerNano);
            updatedAt = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - tokens) / tokensPerNano / 1_000_000_000.0));
        }

        synchronized long idleSince(long now) {
            return now - updatedAt;
        }
    }
}
