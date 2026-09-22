package com.courtpulse.persistence;

/** Sanitized, bounded-cardinality aggregate delivery health. */
public record DeliveryOperations(
        long backlog, double oldestPendingAgeSeconds, long publications, long attempts,
        long successes, long retries, long terminalFailures, long leaseRecoveries,
        long dlqDepth, java.time.Instant dlqObservedAt) {}
