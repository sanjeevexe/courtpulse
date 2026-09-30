package com.courtpulse.api.replays;

import com.courtpulse.api.http.ReplayApiDto;
import com.courtpulse.persistence.JdbcReplayRepository;
import com.courtpulse.persistence.ReplaySession;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.transaction.support.TransactionTemplate;

/** Starting and controlling replays of real games; every signed-in user may run a few at a time. */
public final class ReplayService {
    public static final int MAXIMUM_ACTIVE_PER_USER = 3;
    public static final int MAXIMUM_ACTIVE_TOTAL = 10;
    static final Duration FINISHED_VISIBILITY = Duration.ofHours(6);
    private static final java.util.regex.Pattern TEAM = java.util.regex.Pattern.compile("[A-Z]{2,5}");

    private final JdbcReplayRepository replays;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ReplayService(JdbcReplayRepository replays, TransactionTemplate transactions, Clock clock) {
        this.replays = replays;
        this.transactions = transactions;
        this.clock = clock;
    }

    public ReplayApiDto.ReplayGamePage catalog(String team, String after, Integer limit) {
        int size = limit == null ? 50 : limit;
        if (team != null && !TEAM.matcher(team).matches()) {
            throw new IllegalArgumentException("team must be a team abbreviation such as OKC");
        }
        if (after != null && !after.matches("[0-9]{10}")) {
            throw new IllegalArgumentException("after must be an NBA game ID");
        }
        List<JdbcReplayRepository.CatalogGame> games = replays.catalog(team, after, size + 1);
        boolean more = games.size() > size;
        List<ReplayApiDto.ReplayGame> items = games.stream().limit(size).map(ReplayService::game).toList();
        return new ReplayApiDto.ReplayGamePage(items, more ? items.getLast().nbaGameId() : null);
    }

    /** Running and paused replays, and those finished in the last few hours. */
    public ReplayApiDto.ReplaySessionList sessions() {
        return list(null);
    }

    public ReplayApiDto.ReplaySessionList mine(String subject) {
        return list(Objects.requireNonNull(subject, "subject is required"));
    }

    public ReplayApiDto.ReplaySession start(String subject, String nbaGameId, int speed) {
        ReplaySession session = transactions.execute(status -> {
            replays.lockSessions();
            if (replays.countUnfinished(subject) >= MAXIMUM_ACTIVE_PER_USER) {
                throw new ReplayQuotaExceededException("You can run " + MAXIMUM_ACTIVE_PER_USER
                        + " replays at a time; finish or skip one to the end first.");
            }
            if (replays.countUnfinished(null) >= MAXIMUM_ACTIVE_TOTAL) {
                throw new ReplayQuotaExceededException("The replay server is busy; try again when a replay finishes.");
            }
            return replays.createSession(nbaGameId, speed, subject, clock.instant())
                    .orElseThrow(() -> new ReplayNotFoundException("That game is not in the replay catalog."));
        });
        return view(Objects.requireNonNull(session));
    }

    public ReplayApiDto.ReplaySession pause(String subject, boolean operator, UUID id) {
        return change(subject, operator, id, (session, now) -> {
            requireStatus(session, ReplaySession.Status.RUNNING, "Only a running replay can be paused.");
            return session.paused(now);
        });
    }

    public ReplayApiDto.ReplaySession resume(String subject, boolean operator, UUID id) {
        return change(subject, operator, id, (session, now) -> {
            requireStatus(session, ReplaySession.Status.PAUSED, "Only a paused replay can be resumed.");
            return session.resumed(now);
        });
    }

    public ReplayApiDto.ReplaySession speed(String subject, boolean operator, UUID id, int speed) {
        return change(subject, operator, id, (session, now) -> {
            if (session.status() == ReplaySession.Status.FINISHED) {
                throw new ReplayConflictException("This replay has already finished.");
            }
            return session.withSpeed(speed, now);
        });
    }

    /** Skips to the end: the rest of the game arrives on the next poll and the replay finishes. */
    public ReplayApiDto.ReplaySession finish(String subject, boolean operator, UUID id) {
        return change(subject, operator, id, (session, now) -> {
            if (session.status() == ReplaySession.Status.FINISHED) {
                throw new ReplayConflictException("This replay has already finished.");
            }
            long duration = replays.catalogGame(session.nbaGameId())
                    .map(JdbcReplayRepository.CatalogGame::durationMillis).orElse(Long.MAX_VALUE / 2);
            return session.finished(duration, now);
        });
    }

    private ReplayApiDto.ReplaySession change(String subject, boolean operator, UUID id,
            BiFunction<ReplaySession, java.time.Instant, ReplaySession> transition) {
        ReplaySession updated = transactions.execute(status -> {
            ReplaySession current = replays.session(id)
                    .filter(session -> operator || Objects.equals(session.createdBy(), subject))
                    .orElseThrow(() -> new ReplayNotFoundException("Replay not found."));
            ReplaySession next = transition.apply(current, clock.instant());
            if (!replays.update(current, next)) {
                throw new ReplayConflictException("The replay changed at the same time; try again.");
            }
            return next;
        });
        return view(Objects.requireNonNull(updated));
    }

    private static void requireStatus(ReplaySession session, ReplaySession.Status expected, String message) {
        if (session.status() != expected) {
            throw new ReplayConflictException(message);
        }
    }

    private ReplayApiDto.ReplaySessionList list(String subject) {
        return new ReplayApiDto.ReplaySessionList(replays.activeSessions(clock.instant().minus(FINISHED_VISIBILITY))
                .stream()
                .filter(session -> subject == null || subject.equals(session.createdBy()))
                .map(this::view)
                .toList());
    }

    private ReplayApiDto.ReplaySession view(ReplaySession session) {
        Optional<JdbcReplayRepository.CatalogGame> game = replays.catalogGame(session.nbaGameId());
        long elapsed = session.elapsedAt(clock.instant());
        return new ReplayApiDto.ReplaySession(session.id(), session.nbaGameId(), session.gameId(), session.speed(),
                session.status().name(), replays.releasedCount(session.nbaGameId(), elapsed),
                game.map(JdbcReplayRepository.CatalogGame::actionCount).orElse(0),
                game.map(value -> value.home().name()).orElse(""),
                game.map(value -> value.away().name()).orElse(""),
                game.map(JdbcReplayRepository.CatalogGame::gameDate).orElse(null),
                session.createdAt());
    }

    private static ReplayApiDto.ReplayGame game(JdbcReplayRepository.CatalogGame game) {
        return new ReplayApiDto.ReplayGame(game.nbaGameId(), game.gameDate(), game.home().name(),
                game.home().tricode(), game.away().name(), game.away().tricode(), game.homeScore(),
                game.awayScore(), game.periods(), game.actionCount(), game.durationMillis() / 1000);
    }
}
