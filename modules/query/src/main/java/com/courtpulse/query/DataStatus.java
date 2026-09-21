package com.courtpulse.query;

/** API-facing data freshness state, deliberately separate from basketball game status. */
public enum DataStatus {
    SCHEDULED,
    LIVE,
    FINAL,
    STALE,
    PROCESSING_BLOCKED
}
