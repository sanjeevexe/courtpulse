package com.courtpulse.providers.fixture;

import com.courtpulse.domain.event.CanonicalEvent;
import java.util.List;

public record LoadedFixture(
        int fixtureSchemaVersion,
        String name,
        String description,
        String provenance,
        FixtureGame game,
        List<LoadedSourceEvent> sourceEvents) {

    public LoadedFixture {
        sourceEvents = List.copyOf(sourceEvents);
    }

    public List<CanonicalEvent> events() {
        return sourceEvents.stream().map(LoadedSourceEvent::canonicalEvent).toList();
    }
}
