package com.courtpulse.providers.fixture;

import java.util.List;

public record FixtureDocument(
        int fixtureSchemaVersion,
        String name,
        String description,
        String provenance,
        FixtureGame game,
        List<FixtureEvent> events) {}
