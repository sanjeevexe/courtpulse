package com.courtpulse.query;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class CourtPulseQueryService {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final JdbcCourtPulseReadRepository repository;
    private final OpaqueCursorCodec cursors;

    public CourtPulseQueryService(
            JdbcCourtPulseReadRepository repository, OpaqueCursorCodec cursors) {
        this.repository = repository;
        this.cursors = cursors;
    }

    public KeysetPage<GameSummaryReadModel> games(String status, int limit, String cursor) {
        validateLimit(limit);
        String normalizedStatus = normalizeStatus(status);
        Instant afterUpdatedAt = null;
        String afterGameId = null;
        if (cursor != null) {
            List<String> keys = cursors.decode(cursor, "games", normalizedStatus, 2);
            try {
                afterUpdatedAt = Instant.parse(keys.get(0));
                afterGameId = keys.get(1);
            } catch (RuntimeException exception) {
                throw new InvalidCursorException("Game cursor keys are malformed", exception);
            }
        }
        List<GameSummaryReadModel> rows = repository.listGames(
                normalizedStatus, afterUpdatedAt, afterGameId, limit + 1);
        return page(rows, limit, last -> cursors.encode(
                "games", normalizedStatus, List.of(last.updatedAt().toString(), last.gameId())));
    }

    public GameSnapshotReadModel game(String gameId) {
        return repository.findGameSnapshot(requireGameId(gameId))
                .orElseThrow(() -> new GameNotFoundException(gameId));
    }

    public KeysetPage<CanonicalEventReadModel> events(
            String gameId, int limit, String cursor, Long afterSequence) {
        validateLimit(limit);
        requireExistingGame(gameId);
        if (cursor != null && afterSequence != null) {
            throw new IllegalArgumentException("cursor and afterSequence cannot be combined");
        }
        Long sequence = null;
        Integer revision = null;
        String eventId = null;
        if (cursor != null) {
            List<String> keys = cursors.decode(cursor, "events", gameId, 3);
            try {
                sequence = Long.valueOf(keys.get(0));
                revision = Integer.valueOf(keys.get(1));
                eventId = keys.get(2);
            } catch (RuntimeException exception) {
                throw new InvalidCursorException("Event cursor keys are malformed", exception);
            }
        }
        List<CanonicalEventReadModel> rows = repository.listEvents(
                gameId, afterSequence, sequence, revision, eventId, limit + 1);
        return page(rows, limit, last -> cursors.encode(
                "events",
                gameId,
                List.of(
                        Long.toString(last.sequence()),
                        Integer.toString(last.revision()),
                        last.eventId())));
    }

    public KeysetPage<AlertReadModel> alerts(String gameId, int limit, String cursor) {
        validateLimit(limit);
        requireExistingGame(gameId);
        Instant createdAt = null;
        String triggerKey = null;
        if (cursor != null) {
            List<String> keys = cursors.decode(cursor, "alerts", gameId, 2);
            try {
                createdAt = Instant.parse(keys.get(0));
                triggerKey = keys.get(1);
            } catch (RuntimeException exception) {
                throw new InvalidCursorException("Alert cursor keys are malformed", exception);
            }
        }
        List<AlertReadModel> rows = repository.listAlerts(gameId, createdAt, triggerKey, limit + 1);
        return page(rows, limit, last -> cursors.encode(
                "alerts", gameId, List.of(last.createdAt().toString(), last.triggerKey())));
    }

    public ProcessingHealthReadModel processingHealth() {
        return repository.processingHealth();
    }

    private void requireExistingGame(String gameId) {
        requireGameId(gameId);
        if (!repository.gameExists(gameId)) {
            throw new GameNotFoundException(gameId);
        }
    }

    private static String requireGameId(String gameId) {
        if (gameId == null || gameId.isBlank() || gameId.length() > 200) {
            throw new IllegalArgumentException("gameId must be between 1 and 200 characters");
        }
        return gameId;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        if (!List.of("SCHEDULED", "LIVE", "FINAL").contains(normalized)) {
            throw new IllegalArgumentException("status must be SCHEDULED, LIVE, or FINAL");
        }
        return normalized;
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    private static <T> KeysetPage<T> page(
            List<T> rows, int limit, java.util.function.Function<T, String> cursorFactory) {
        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? cursorFactory.apply(items.getLast()) : null;
        return new KeysetPage<>(items, nextCursor);
    }
}
