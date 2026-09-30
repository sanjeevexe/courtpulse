package com.courtpulse.queuereplay;

import com.courtpulse.persistence.JdbcOperationalTelemetryRepository;
import com.courtpulse.persistence.ProviderIngestionService;
import com.courtpulse.providers.live.LiveGameProvider;
import com.courtpulse.providers.live.ProviderException;
import com.courtpulse.providers.live.ProviderGame;
import com.courtpulse.providers.nba.NbaReplayPlayMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls one live provider: discovers today's and yesterday's league games, polls plays only for
 * games that need them, resolves a few unknown player names, and records client health. Provider
 * failures never stop the loop; they surface as stale freshness, failure counts, and an open
 * circuit, while the heartbeat keeps proving the worker itself is alive.
 */
final class LiveIngestDaemon {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveIngestDaemon.class);
    private static final Path HEARTBEAT = Path.of("/tmp/courtpulse-ingestor-worker.heartbeat");
    private static final Set<String> STOP_CYCLE = Set.of("circuit_open", "rate_limited_local", "http_401", "http_403");

    private final LiveGameProvider provider;
    private final ProviderIngestionService ingestion;
    private final JdbcOperationalTelemetryRepository telemetry;
    private final Settings settings;
    private final Clock clock;
    private final Set<String> unresolvablePlayers = new HashSet<>();
    private String lastDiscoveryFailure;
    private final String workerType;

    LiveIngestDaemon(LiveGameProvider provider, ProviderIngestionService ingestion,
            JdbcOperationalTelemetryRepository telemetry, Settings settings, Clock clock) {
        this.provider = provider;
        this.ingestion = ingestion;
        this.telemetry = telemetry;
        this.settings = settings;
        this.clock = clock;
        this.workerType = NbaReplayPlayMapper.SOURCE.equals(provider.source()) ? "replay" : "ingestor";
    }

    void run() {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            main.interrupt();
        }, "ingest-shutdown"));
        LOGGER.atInfo().addKeyValue("source", provider.source())
                .addKeyValue("pollInterval", settings.pollInterval())
                .log("Live ingestion started");
        while (running.get()) {
            Instant started = clock.instant();
            boolean healthy = cycle();
            ingestion.recordClientStats(provider.source(), provider.drainStats());
            telemetry.heartbeat(workerType, clock.instant(), healthy);
            try {
                Files.writeString(HEARTBEAT, clock.instant().toString(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Duration remaining = settings.pollInterval().minus(Duration.between(started, clock.instant()));
                if (!remaining.isNegative()) {
                    Thread.sleep(remaining);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException exception) {
                throw new IllegalStateException("Ingestor heartbeat failed", exception);
            }
        }
    }

    /** One bounded pass; returns whether the provider answered discovery. */
    boolean cycle() {
        List<ProviderGame> games;
        try {
            games = provider.discover(leagueDates());
            ingestion.recordDiscovery(games);
        } catch (ProviderException exception) {
            // Log transitions, not every polling interval of a known outage or open circuit.
            if (!exception.code().equals(lastDiscoveryFailure)) {
                LOGGER.atWarn().addKeyValue("code", exception.code())
                        .log("Provider discovery failed: {}", exception.code());
            }
            lastDiscoveryFailure = exception.code();
            return false;
        }
        if (lastDiscoveryFailure != null) {
            LOGGER.atInfo().addKeyValue("previousCode", lastDiscoveryFailure).log("Provider discovery recovered");
            lastDiscoveryFailure = null;
        }
        int polled = 0;
        for (ProviderGame game : games) {
            if (polled >= settings.maximumLiveGames()) {
                LOGGER.atWarn().addKeyValue("limit", settings.maximumLiveGames())
                        .log("Live game polling limit reached for this cycle");
                break;
            }
            if (!ingestion.needsPlays(game)) {
                continue;
            }
            polled++;
            try {
                var result = ingestion.ingest(game, provider.plays(game));
                if (result.newEvents() > 0 || result.corrections() > 0 || result.rejected() > 0
                        || result.derivedFinal()) {
                    LOGGER.atInfo().addKeyValue("gameId", game.gameId())
                            .addKeyValue("newEvents", result.newEvents())
                            .addKeyValue("corrections", result.corrections())
                            .addKeyValue("rejected", result.rejected())
                            .addKeyValue("derivedFinal", result.derivedFinal())
                            .log("Provider feed ingested");
                }
            } catch (ProviderException exception) {
                ingestion.recordFailure(game, exception.code());
                LOGGER.atWarn().addKeyValue("gameId", game.gameId()).addKeyValue("code", exception.code())
                        .log("Provider play poll failed for {}: {}", game.gameId(), exception.code());
                if (STOP_CYCLE.contains(exception.code())) {
                    break;
                }
            }
        }
        resolvePlayers();
        return true;
    }

    int ingestOne(String providerGameId) {
        ProviderGame game = provider.game(providerGameId)
                .orElseThrow(() -> new IllegalArgumentException("Provider game was not found"));
        ingestion.recordDiscovery(List.of(game));
        var result = ingestion.ingest(game, provider.plays(game));
        resolvePlayers();
        ingestion.recordClientStats(provider.source(), provider.drainStats());
        System.out.printf("Provider game %s (%s): new=%d corrections=%d unchanged=%d rejected=%d derivedFinal=%s%n",
                game.gameId(), game.providerStatus(), result.newEvents(), result.corrections(),
                result.unchanged(), result.rejected(), result.derivedFinal());
        return result.newEvents();
    }

    private void resolvePlayers() {
        int budget = settings.playerLookupsPerCycle();
        for (String playerId : ingestion.unknownPlayerIds(provider.source(), budget + unresolvablePlayers.size())) {
            if (budget <= 0) {
                return;
            }
            if (unresolvablePlayers.contains(playerId)) {
                continue;
            }
            var providerId = provider.providerPlayerId(playerId);
            if (providerId.isEmpty()) {
                unresolvablePlayers.add(playerId);
                continue;
            }
            budget--;
            try {
                provider.player(providerId.get()).ifPresentOrElse(
                        player -> ingestion.savePlayer(provider.source(), player),
                        () -> unresolvablePlayers.add(playerId));
            } catch (ProviderException exception) {
                if (!exception.retryable()) {
                    unresolvablePlayers.add(playerId);
                }
                return;
            }
        }
    }

    private List<LocalDate> leagueDates() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), settings.leagueZone());
        return List.of(today.minusDays(1), today);
    }

    record Settings(Duration pollInterval, int maximumLiveGames, int playerLookupsPerCycle, ZoneId leagueZone) {
        Settings {
            if (pollInterval.compareTo(Duration.ofSeconds(1)) < 0 || pollInterval.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("ingest poll interval must be between 1s and 10m");
            }
            if (maximumLiveGames < 1 || maximumLiveGames > 50) {
                throw new IllegalArgumentException("maximum live games must be between 1 and 50");
            }
            if (playerLookupsPerCycle < 0 || playerLookupsPerCycle > 50) {
                throw new IllegalArgumentException("player lookups per cycle must be between 0 and 50");
            }
        }
    }
}
