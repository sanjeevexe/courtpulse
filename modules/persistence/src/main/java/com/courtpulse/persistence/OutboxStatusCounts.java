package com.courtpulse.persistence;

public record OutboxStatusCounts(
        long pending,
        long publishing,
        long retryScheduled,
        long sent,
        long failed,
        long deferred) {}
