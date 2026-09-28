package com.courtpulse.domain.event;

import com.courtpulse.domain.game.GamePeriods;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable, provider-neutral event contract used by every domain consumer. */
public record CanonicalEvent(
        String eventId,
        int schemaVersion,
        String gameId,
        String source,
        String providerEventId,
        long sequence,
        int revision,
        EventType type,
        int period,
        long clockMillisRemaining,
        Instant occurredAt,
        String teamId,
        List<String> participantIds,
        Score scoreAfter,
        int points,
        String description) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    /** Provider play text is display-only evidence; it never drives state or rules. */
    public static final int MAXIMUM_DESCRIPTION_LENGTH = 280;

    /** Events without provider text, such as the synthetic fixtures. */
    public CanonicalEvent(
            String eventId, int schemaVersion, String gameId, String source,
            String providerEventId, long sequence, int revision, EventType type, int period,
            long clockMillisRemaining, Instant occurredAt, String teamId,
            List<String> participantIds, Score scoreAfter, int points) {
        this(eventId, schemaVersion, gameId, source, providerEventId, sequence, revision, type,
                period, clockMillisRemaining, occurredAt, teamId, participantIds, scoreAfter,
                points, null);
    }

    public CanonicalEvent {
        eventId = requireText(eventId, "eventId");
        gameId = requireText(gameId, "gameId");
        source = requireText(source, "source");
        providerEventId = requireText(providerEventId, "providerEventId");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        participantIds = participantIds == null ? List.of() : List.copyOf(participantIds);

        if (participantIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("participantIds must not contain blank values");
        }
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported canonical event schemaVersion: " + schemaVersion);
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be at least 1");
        }
        if (period < 1 || period > GamePeriods.MAXIMUM_PERIOD) {
            throw new IllegalArgumentException(
                    "period must be between 1 and " + GamePeriods.MAXIMUM_PERIOD);
        }
        long periodMillis = GamePeriods.maximumClockMillis(period);
        if (clockMillisRemaining < 0 || clockMillisRemaining > periodMillis) {
            throw new IllegalArgumentException(
                    "clockMillisRemaining must be between 0 and " + periodMillis
                            + " in period " + period);
        }
        if (description != null) {
            if (description.isBlank() || !description.equals(description.strip())
                    || description.length() > MAXIMUM_DESCRIPTION_LENGTH
                    || description.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "description must be trimmed, printable, and at most "
                                + MAXIMUM_DESCRIPTION_LENGTH + " characters");
            }
        }
        Objects.requireNonNull(scoreAfter, "scoreAfter is required");

        if (type == EventType.FIELD_GOAL_MADE || type == EventType.FREE_THROW_MADE) {
            teamId = requireText(teamId, "teamId");
            if (participantIds.isEmpty()) {
                throw new IllegalArgumentException("A scoring event requires the scorer as participantIds[0]");
            }
            if (points < 1 || points > 3) {
                throw new IllegalArgumentException("A scoring event must add between 1 and 3 points");
            }
            if (type == EventType.FREE_THROW_MADE && points != 1) {
                throw new IllegalArgumentException("A made free throw must add exactly 1 point");
            }
        } else {
            if (points != 0) {
                throw new IllegalArgumentException(type + " must not add points");
            }
            if (teamId != null && teamId.isBlank()) {
                throw new IllegalArgumentException("teamId must be null or non-blank");
            }
        }
    }

    public EventIdentity identity() {
        return new EventIdentity(source, providerEventId, revision);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
