package com.courtpulse.providers.fixture;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventType;
import com.courtpulse.providers.SourceEventMapper;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class FixtureEventMapper implements SourceEventMapper<FixtureEvent> {
    @Override
    public CanonicalEvent toCanonicalEvent(FixtureEvent event) {
        EventType eventType;
        try {
            eventType = EventType.valueOf(event.type());
        } catch (RuntimeException exception) {
            String supported = Arrays.stream(EventType.values())
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            throw new FixtureFormatException(
                    "Unsupported event type '" + event.type() + "'. Supported types: " + supported,
                    exception);
        }

        return new CanonicalEvent(
                event.eventId(),
                event.schemaVersion(),
                event.gameId(),
                event.source(),
                event.providerEventId(),
                event.sequence(),
                event.revision(),
                eventType,
                event.period(),
                event.clockMillisRemaining(),
                event.occurredAt(),
                event.teamId(),
                event.participantIds(),
                event.scoreAfter(),
                event.points());
    }
}
