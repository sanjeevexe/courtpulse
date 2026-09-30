package com.courtpulse.persistence;

import com.courtpulse.providers.live.LiveGameProvider;
import com.courtpulse.providers.live.ProviderClientStats;
import com.courtpulse.providers.live.ProviderGame;
import com.courtpulse.providers.live.ProviderLifecycle;
import com.courtpulse.providers.live.ProviderPlay;
import com.courtpulse.providers.live.ProviderPlayer;
import com.courtpulse.providers.nba.NbaReplayPlayMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Serves replay sessions as if they were a live provider: each session is a game whose plays are
 * released as replay time reaches their offsets. Nothing downstream knows the difference, so a
 * replay exercises the real ingestion, processing, alert, and realtime paths.
 */
public final class NbaReplayProvider implements LiveGameProvider {
    /** Finished replays stay discoverable briefly so ingestion observes the final state. */
    static final Duration FINISHED_VISIBILITY = Duration.ofMinutes(15);
    private static final Pattern PERSON_ID = Pattern.compile("^[1-9][0-9]{0,11}$");

    private final JdbcReplayRepository replays;
    private final NbaReplayPlayMapper mapper;
    private final Clock clock;

    public NbaReplayProvider(JdbcReplayRepository replays, ObjectMapper objectMapper, Clock clock) {
        this.replays = replays;
        this.mapper = new NbaReplayPlayMapper(objectMapper);
        this.clock = clock;
    }

    @Override
    public String source() {
        return NbaReplayPlayMapper.SOURCE;
    }

    /** Replays are not tied to league dates; every active replay is "on the schedule". */
    @Override
    public List<ProviderGame> discover(List<LocalDate> leagueDates) {
        return replays.activeSessions(clock.instant().minus(FINISHED_VISIBILITY)).stream()
                .map(this::providerGame).flatMap(Optional::stream).toList();
    }

    @Override
    public Optional<ProviderGame> game(String providerGameId) {
        return replays.sessionForProviderGame(providerGameId).flatMap(this::providerGame);
    }

    @Override
    public List<ProviderPlay> plays(ProviderGame game) {
        ReplaySession session = replays.sessionForProviderGame(game.providerGameId()).orElse(null);
        if (session == null) {
            return List.of();
        }
        return replays.releasedActions(session.nbaGameId(), session.elapsedAt(clock.instant())).stream()
                .map(action -> mapper.map(action, game)).toList();
    }

    @Override
    public Optional<ProviderPlayer> player(String providerPlayerId) {
        if (!PERSON_ID.matcher(providerPlayerId).matches()) {
            return Optional.empty();
        }
        long personId = Long.parseLong(providerPlayerId);
        return replays.playerName(personId).map(name ->
                new ProviderPlayer(NbaReplayPlayMapper.playerId(personId), providerPlayerId, name));
    }

    @Override
    public Optional<String> providerPlayerId(String playerId) {
        return playerId.startsWith(NbaReplayPlayMapper.PLAYER_PREFIX)
                ? Optional.of(playerId.substring(NbaReplayPlayMapper.PLAYER_PREFIX.length()))
                : Optional.empty();
    }

    @Override
    public ProviderClientStats drainStats() {
        // Local database reads: no quota, no circuit, and every poll succeeds.
        return new ProviderClientStats(0, 0, 0, "CLOSED", null, clock.instant(), clock.instant(), 0);
    }

    private Optional<ProviderGame> providerGame(ReplaySession session) {
        return replays.catalogGame(session.nbaGameId()).map(catalog -> {
            long elapsed = session.elapsedAt(clock.instant());
            boolean ended = elapsed >= catalog.durationMillis();
            ProviderLifecycle lifecycle = ended ? ProviderLifecycle.FINAL : ProviderLifecycle.LIVE;
            if (ended && session.status() == ReplaySession.Status.RUNNING) {
                replays.update(session, session.finished(catalog.durationMillis(), clock.instant()));
            }
            return new ProviderGame(source(), session.providerGameId(), session.gameId(),
                    NbaReplayPlayMapper.team(catalog.home()), NbaReplayPlayMapper.team(catalog.away()),
                    session.createdAt(), ended ? "Final" : session.status() == ReplaySession.Status.PAUSED
                            ? "Paused" : "Replaying " + session.speed() + "x",
                    lifecycle);
        });
    }
}
