package com.courtpulse.api.http;

import com.courtpulse.observability.TraceContext;
import io.opentelemetry.api.trace.SpanKind;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-ID";
    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        String requested = request.getHeader(HEADER);
        String correlationId = requested != null && SAFE.matcher(requested).matches()
                ? requested
                : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        MDC.put("correlationId", correlationId);
        try (var span = TraceContext.start("http request", SpanKind.INTERNAL)) {
            String traceparent = TraceContext.currentTraceparent();
            if (traceparent != null) {
                response.setHeader("X-Trace-ID", span.span().getSpanContext().getTraceId());
            }
            chain.doFilter(request, response);
        } finally {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = pattern == null ? "unmatched" : pattern.toString();
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            LOGGER.atInfo()
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("route", route)
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("elapsedMs", elapsedMillis)
                    .addKeyValue("correlationId", correlationId)
                    .log("HTTP request completed");
            MDC.remove("correlationId");
        }
    }
}
