package com.courtpulse.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One replay of a real game. Replay time advances at {@code speed} times wall time while running;
 * pausing, resuming, and changing speed fold the running segment into {@code elapsedMillis} and
 * restart the segment at {@code anchorAt}, so replay time is continuous across every change.
 */
public record ReplaySession(
        UUID id,
        String nbaGameId,
        int runNumber,
        String gameId,
        int speed,
        Status status,
        long elapsedMillis,
        Instant anchorAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {
    public static final int MINIMUM_SPEED = 1;
    public static final int MAXIMUM_SPEED = 120;

    public enum Status { RUNNING, PAUSED, FINISHED }

    public ReplaySession {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(anchorAt, "anchorAt is required");
        requireSpeed(speed);
    }

    public static void requireSpeed(int speed) {
        if (speed < MINIMUM_SPEED || speed > MAXIMUM_SPEED) {
            throw new IllegalArgumentException("speed must be between " + MINIMUM_SPEED + " and " + MAXIMUM_SPEED);
        }
    }

    /** Provider game ID for this run, unique per real game and run. */
    public String providerGameId() {
        return nbaGameId + "-" + runNumber;
    }

    /** Replay time reached at {@code now}. */
    public long elapsedAt(Instant now) {
        if (status != Status.RUNNING) {
            return elapsedMillis;
        }
        long wall = Math.max(0, Duration.between(anchorAt, now).toMillis());
        return Math.addExact(elapsedMillis, Math.multiplyExact(wall, (long) speed));
    }

    public ReplaySession paused(Instant now) {
        return new ReplaySession(id, nbaGameId, runNumber, gameId, speed, Status.PAUSED, elapsedAt(now), now,
                createdBy, createdAt, now, version + 1);
    }

    public ReplaySession resumed(Instant now) {
        return new ReplaySession(id, nbaGameId, runNumber, gameId, speed, Status.RUNNING, elapsedMillis, now,
                createdBy, createdAt, now, version + 1);
    }

    public ReplaySession withSpeed(int newSpeed, Instant now) {
        requireSpeed(newSpeed);
        return new ReplaySession(id, nbaGameId, runNumber, gameId, newSpeed, status, elapsedAt(now), now,
                createdBy, createdAt, now, version + 1);
    }

    /** Jumps to the end: every remaining action is released on the next poll. */
    public ReplaySession finished(long durationMillis, Instant now) {
        return new ReplaySession(id, nbaGameId, runNumber, gameId, speed, Status.FINISHED,
                Math.max(elapsedAt(now), durationMillis), now, createdBy, createdAt, now, version + 1);
    }
}
