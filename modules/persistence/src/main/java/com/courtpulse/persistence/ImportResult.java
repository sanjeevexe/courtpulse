package com.courtpulse.persistence;

public record ImportResult(
        long insertedRawPayloads,
        long insertedCanonicalEvents,
        long insertedOutboxRecords) {}
