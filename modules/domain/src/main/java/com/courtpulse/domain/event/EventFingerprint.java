package com.courtpulse.domain.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable compact representation used to validate redelivered canonical events. */
public final class EventFingerprint {
    public static final int REPRESENTATION_VERSION = 1;

    private EventFingerprint() {}

    public static String sha256(CanonicalEvent event) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "eventFingerprintVersion", Integer.toString(REPRESENTATION_VERSION));
        append(canonical, "eventId", event.eventId());
        append(canonical, "schemaVersion", Integer.toString(event.schemaVersion()));
        append(canonical, "gameId", event.gameId());
        append(canonical, "source", event.source());
        append(canonical, "providerEventId", event.providerEventId());
        append(canonical, "sequence", Long.toString(event.sequence()));
        append(canonical, "revision", Integer.toString(event.revision()));
        append(canonical, "type", event.type().name());
        append(canonical, "period", Integer.toString(event.period()));
        append(canonical, "clockMillisRemaining", Long.toString(event.clockMillisRemaining()));
        append(canonical, "occurredAt", event.occurredAt().toString());
        append(canonical, "teamId", event.teamId());
        append(canonical, "participantCount", Integer.toString(event.participantIds().size()));
        for (String participantId : event.participantIds()) {
            append(canonical, "participantId", participantId);
        }
        append(canonical, "homeScore", Integer.toString(event.scoreAfter().home()));
        append(canonical, "awayScore", Integer.toString(event.scoreAfter().away()));
        append(canonical, "points", Integer.toString(event.points()));
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder canonical, String field, String value) {
        canonical.append(field).append('=');
        if (value == null) {
            canonical.append("-1:");
        } else {
            canonical.append(value.length()).append(':').append(value);
        }
        canonical.append('\n');
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }
}
