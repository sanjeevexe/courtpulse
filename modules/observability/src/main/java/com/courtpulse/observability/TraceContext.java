package com.courtpulse.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** Only a validated W3C traceparent is persisted or sent through a queue. */
public final class TraceContext {
    private static final Pattern TRACEPARENT = Pattern.compile(
            "00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");

    private TraceContext() {}

    public static SpanScope start(String name, SpanKind kind) {
        return new SpanScope(GlobalOpenTelemetry.getTracer("com.courtpulse")
                .spanBuilder(name).setSpanKind(kind).startSpan());
    }

    public static SpanScope continueFrom(String traceparent, String name, SpanKind kind) {
        SpanContext parent = parse(traceparent);
        if (parent == null) {
            return start(name, kind);
        }
        Context remote = Context.root().with(Span.wrap(parent));
        Span span = GlobalOpenTelemetry.getTracer("com.courtpulse")
                .spanBuilder(name).setSpanKind(kind).setParent(remote).startSpan();
        return new SpanScope(span);
    }

    /**
     * A span that is not made current, for work handled in batches: each item gets its own span
     * (continuing its stored context, or a new root when it has none) without nesting under the
     * previous item. The caller must end it.
     */
    public static Span startDetached(String traceparent, String name, SpanKind kind) {
        SpanContext parent = parse(traceparent);
        var builder = GlobalOpenTelemetry.getTracer("com.courtpulse").spanBuilder(name).setSpanKind(kind);
        if (parent == null) {
            builder.setNoParent();
        } else {
            builder.setParent(Context.root().with(Span.wrap(parent)));
        }
        return builder.startSpan();
    }

    public static String currentTraceparent() {
        return format(Span.current().getSpanContext());
    }

    public static String format(SpanContext context) {
        if (context == null || !context.isValid()) {
            return null;
        }
        return "00-" + context.getTraceId() + "-" + context.getSpanId()
                + "-" + context.getTraceFlags().asHex();
    }

    public static SpanContext parse(String value) {
        if (value == null) {
            return null;
        }
        Matcher match = TRACEPARENT.matcher(value);
        if (!match.matches()) {
            return null;
        }
        SpanContext context = SpanContext.createFromRemoteParent(
                match.group(1), match.group(2), TraceFlags.fromHex(match.group(3), 0),
                TraceState.getDefault());
        return context.isValid() ? context : null;
    }

    public static final class SpanScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private final String priorTraceId;

        private SpanScope(Span span) {
            this.span = span;
            this.priorTraceId = MDC.get("traceId");
            this.scope = span.makeCurrent();
            if (span.getSpanContext().isValid()) {
                MDC.put("traceId", span.getSpanContext().getTraceId());
            }
        }

        public Span span() {
            return span;
        }

        @Override
        public void close() {
            try {
                scope.close();
                span.end();
            } finally {
                if (priorTraceId == null) {
                    MDC.remove("traceId");
                } else {
                    MDC.put("traceId", priorTraceId);
                }
            }
        }
    }
}
