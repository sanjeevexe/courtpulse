package com.courtpulse.providers.live;

import java.time.Duration;
import java.util.Set;

/** Failure with a fixed, telemetry-safe code; never carries provider bodies or credentials. */
public final class ProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public static final Set<String> CODES = Set.of(
            "timeout", "connection_failed", "http_401", "http_403", "http_404", "http_429",
            "http_4xx", "http_5xx", "malformed_response", "response_too_large", "circuit_open",
            "rate_limited_local", "interrupted");

    private final String code;
    private final boolean retryable;
    private final transient Duration retryAfter;

    public ProviderException(String code, boolean retryable, Duration retryAfter) {
        super(code);
        if (!CODES.contains(code)) {
            throw new IllegalArgumentException("Unknown provider error code");
        }
        this.code = code;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public ProviderException(String code, boolean retryable) {
        this(code, retryable, null);
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public Duration retryAfter() { return retryAfter; }
}
