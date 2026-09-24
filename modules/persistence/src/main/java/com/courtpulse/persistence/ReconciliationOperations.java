package com.courtpulse.persistence;

public record ReconciliationOperations(long pending, long rebuilding, long blocked,
        long completed, long unchanged, long failedAttempts, Long oldestPendingAgeSeconds) {}
