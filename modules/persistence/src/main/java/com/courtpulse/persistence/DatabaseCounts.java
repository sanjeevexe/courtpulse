package com.courtpulse.persistence;

public record DatabaseCounts(
        long games,
        long rawProviderPayloads,
        long canonicalEvents,
        long gameCheckpoints,
        long processedEvents,
        long alertInstances,
        long outboxRecords,
        long replayRuns) {}
