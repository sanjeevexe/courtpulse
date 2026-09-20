package com.courtpulse.providers.fixture;

import com.courtpulse.domain.event.CanonicalEvent;
import java.util.List;

public record LoadedFixture(
        int fixtureSchemaVersion,
        String name,
        String description,
        String provenance,
        FixtureGame game,
        List<CanonicalEvent> events) {

    public LoadedFixture {
        events = List.copyOf(events);
    }
}
