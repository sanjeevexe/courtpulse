package com.courtpulse.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TraceContextTest {
    @BeforeAll
    static void configureTracer() {
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build()).build());
    }

    @Test
    void persistedContextLinksLaterQueueConsumerToOriginalTrace() {
        String propagated;
        try (var ingest = TraceContext.start("fixture ingest", SpanKind.INTERNAL)) {
            propagated = TraceContext.currentTraceparent();
            assertEquals(ingest.span().getSpanContext().getTraceId(),
                    TraceContext.parse(propagated).getTraceId());
        }
        try (var consumer = TraceContext.continueFrom(propagated,
                "game-event consume", SpanKind.CONSUMER)) {
            assertEquals(TraceContext.parse(propagated).getTraceId(),
                    consumer.span().getSpanContext().getTraceId());
            assertFalse(TraceContext.parse(propagated).getSpanId().equals(
                    consumer.span().getSpanContext().getSpanId()));
        }
    }

    @Test
    void acceptsOnlyValidTraceparentWithoutPrivateBaggage() {
        String parent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        SpanContext parsed = TraceContext.parse(parent);
        assertTrue(parsed.isValid());
        assertEquals(parent, TraceContext.format(parsed));
        assertNull(TraceContext.parse(parent + " email=person@example.test"));
        assertNull(TraceContext.parse("00-00000000000000000000000000000000-0123456789abcdef-01"));
        assertNull(TraceContext.parse("00-0123456789abcdef0123456789abcdef-0000000000000000-01"));
        assertNull(TraceContext.parse("Bearer secret"));
    }
}
