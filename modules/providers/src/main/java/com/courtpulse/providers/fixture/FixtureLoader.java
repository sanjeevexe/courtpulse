package com.courtpulse.providers.fixture;

import com.courtpulse.domain.event.CanonicalEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FixtureLoader {
    public static final int CURRENT_FIXTURE_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final FixtureEventMapper eventMapper;

    public FixtureLoader() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.eventMapper = new FixtureEventMapper();
    }

    public LoadedFixture load(InputStream inputStream) {
        Objects.requireNonNull(inputStream, "inputStream is required");
        try {
            FixtureDocument source = objectMapper.readValue(inputStream, FixtureDocument.class);
            validateDocument(source);
            List<CanonicalEvent> events = new ArrayList<>();
            for (FixtureEvent event : source.events()) {
                CanonicalEvent canonicalEvent = eventMapper.toCanonicalEvent(event);
                if (!source.game().gameId().equals(canonicalEvent.gameId())) {
                    throw new FixtureFormatException(
                            "Event " + canonicalEvent.eventId() + " has gameId "
                                    + canonicalEvent.gameId() + " but fixture gameId is "
                                    + source.game().gameId());
                }
                events.add(canonicalEvent);
            }
            return new LoadedFixture(
                    source.fixtureSchemaVersion(),
                    source.name(),
                    source.description(),
                    source.provenance(),
                    source.game(),
                    events);
        } catch (FixtureFormatException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new FixtureFormatException(
                    "Invalid CourtPulse fixture JSON: " + exception.getOriginalMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new FixtureFormatException(
                    "Invalid CourtPulse fixture: " + exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new FixtureFormatException("Unable to read CourtPulse fixture", exception);
        }
    }

    private static void validateDocument(FixtureDocument document) {
        if (document.fixtureSchemaVersion() != CURRENT_FIXTURE_SCHEMA_VERSION) {
            throw new FixtureFormatException(
                    "Unsupported fixtureSchemaVersion: " + document.fixtureSchemaVersion());
        }
        requireText(document.name(), "name");
        requireText(document.description(), "description");
        requireText(document.provenance(), "provenance");
        if (document.game() == null) {
            throw new FixtureFormatException("Fixture game metadata is required");
        }
        requireText(document.game().gameId(), "game.gameId");
        requireText(document.game().homeTeamId(), "game.homeTeamId");
        requireText(document.game().awayTeamId(), "game.awayTeamId");
        if (document.events() == null || document.events().isEmpty()) {
            throw new FixtureFormatException("Fixture must contain at least one source event");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new FixtureFormatException(field + " is required and must not be blank");
        }
    }
}
