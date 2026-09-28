package com.courtpulse.persistence;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.event.EventType;
import com.courtpulse.observability.TraceContext;
import com.courtpulse.providers.fixture.FixtureGame;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.providers.fixture.LoadedSourceEvent;
import com.courtpulse.providers.live.ProviderClientStats;
import com.courtpulse.providers.live.ProviderGame;
import com.courtpulse.providers.live.ProviderLifecycle;
import com.courtpulse.providers.live.ProviderPlay;
import com.courtpulse.providers.live.ProviderPlayer;
import io.opentelemetry.api.trace.SpanKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns one provider poll into immutable evidence. New provider identities go forward through
 * the transactional outbox; changed content becomes a higher revision handled by reconciliation;
 * unmappable, reverted, or vanished plays become data-quality incidents. Every successful poll
 * records freshness, which is what keeps a quiet live game (a timeout, halftime) from looking
 * stale and a failing provider from looking fresh.
 */
public final class ProviderIngestionService {
    /** How long a provider-final game may go without an End Game play before one is derived. */
    public static final Duration DERIVED_FINAL_GRACE = Duration.ofSeconds(60);

    private final JdbcProviderRepository providers;
    private final JdbcFixtureRepository fixtures;
    private final JdbcOutboxRepository outbox;
    private final GameReconciliationService reconciliation;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final FinalRefetch finalRefetch;

    public ProviderIngestionService(JdbcProviderRepository providers, JdbcFixtureRepository fixtures,
            JdbcOutboxRepository outbox, GameReconciliationService reconciliation,
            TransactionTemplate transactions, Clock clock) {
        this(providers, fixtures, outbox, reconciliation, transactions, clock, FinalRefetch.DEFAULT);
    }

    public ProviderIngestionService(JdbcProviderRepository providers, JdbcFixtureRepository fixtures,
            JdbcOutboxRepository outbox, GameReconciliationService reconciliation,
            TransactionTemplate transactions, Clock clock, FinalRefetch finalRefetch) {
        this.providers = providers;
        this.fixtures = fixtures;
        this.outbox = outbox;
        this.reconciliation = reconciliation;
        this.transactions = transactions;
        this.clock = clock;
        this.finalRefetch = finalRefetch;
    }

    /** Registers discovered games and teams so scheduled games appear before tip-off. */
    public void recordDiscovery(List<ProviderGame> games) {
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            for (ProviderGame game : games) {
                register(game, now);
                providers.observeGame(game, now);
            }
        });
    }

    /**
     * Live games; provider-final games until CourtPulse is final; and, for a bounded window after
     * the final, an occasional refetch because providers correct box scores after games end.
     */
    public boolean needsPlays(ProviderGame game) {
        return switch (game.lifecycle()) {
            case LIVE -> true;
            case FINAL -> !providers.checkpointFinal(game.gameId())
                    || providers.dueForFinalRefetch(game.gameId(), clock.instant(),
                            finalRefetch.window(), finalRefetch.interval());
            case SCHEDULED -> false;
        };
    }

    public FeedResult ingest(ProviderGame game, List<ProviderPlay> plays) {
        try (var span = TraceContext.start("provider ingest", SpanKind.INTERNAL)) {
            span.span().setAttribute("courtpulse.provider.game.id", game.providerGameId());
            FeedResult result = transactions.execute(status -> ingestInTransaction(game, plays));
            span.span().setAttribute("courtpulse.provider.new.events", result.newEvents());
            span.span().setAttribute("courtpulse.provider.corrections", result.corrections());
            return result;
        }
    }

    public void recordFailure(ProviderGame game, String errorCode) {
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            register(game, now);
            providers.recordPollFailure(game, errorCode, now);
        });
    }

    public List<String> unknownPlayerIds(String source, int limit) {
        return providers.unknownPlayerIds(source, clock.instant().minus(Duration.ofDays(3)), limit);
    }

    public void savePlayer(String source, ProviderPlayer player) {
        providers.savePlayer(source, player, clock.instant());
    }

    public void recordClientStats(String source, ProviderClientStats stats) {
        providers.recordClientStats(source, stats, clock.instant());
    }

    private FeedResult ingestInTransaction(ProviderGame game, List<ProviderPlay> plays) {
        Instant now = clock.instant();
        FixtureGame fixtureGame = register(game, now);
        Map<String, JdbcProviderRepository.StoredRevisions> stored =
                providers.storedRevisions(game.source(), game.gameId());
        List<LoadedSourceEvent> forward = new ArrayList<>();
        List<LoadedSourceEvent> corrections = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int unchanged = 0;
        int rejected = 0;
        boolean providerFinalPlay = false;
        CanonicalEvent latestMapped = null;

        for (ProviderPlay play : plays) {
            seen.add(play.providerEventId());
            if (play.rejected()) {
                providers.recordIncident(game.source(), game.gameId(), play.providerEventId(),
                        "MAPPING_REJECTED", play.rejectionCode(), play.rawPayload(), now);
                rejected++;
                continue;
            }
            JdbcProviderRepository.StoredRevisions existing = stored.get(play.providerEventId());
            CanonicalEvent event;
            if (existing == null) {
                event = play.atRevision(1);
                forward.add(new LoadedSourceEvent(event, play.rawPayload(), play.contentHash()));
            } else if (existing.latestHash().equals(play.contentHash())) {
                event = play.atRevision(existing.latestRevision());
                unchanged++;
            } else if (existing.hashes().contains(play.contentHash())) {
                // Returning to an older body would reuse a stored content hash; an operator decides.
                providers.recordIncident(game.source(), game.gameId(), play.providerEventId(),
                        "REVERTED_CONTENT", "reverted_to_earlier_revision", null, now);
                rejected++;
                continue;
            } else {
                event = play.atRevision(existing.latestRevision() + 1);
                corrections.add(new LoadedSourceEvent(event, play.rawPayload(), play.contentHash()));
            }
            providerFinalPlay |= event.type() == EventType.GAME_FINAL;
            if (latestMapped == null || event.sequence() > latestMapped.sequence()) {
                latestMapped = event;
            }
        }

        if (!plays.isEmpty()) {
            for (String vanished : stored.keySet()) {
                if (!seen.contains(vanished) && !vanished.endsWith(":final")) {
                    providers.recordIncident(game.source(), game.gameId(), vanished,
                            "PLAY_REMOVED", "identity_absent_from_provider_feed", null, now);
                }
            }
        }

        insertForward(forward, now);
        if (!corrections.isEmpty()) {
            reconciliation.submit(new LoadedFixture(1, "provider-correction",
                    "Revised provider evidence observed by polling", game.source() + " poll",
                    fixtureGame, corrections));
        }

        boolean derivedFinal = false;
        if (game.lifecycle() == ProviderLifecycle.FINAL && !providerFinalPlay && latestMapped != null
                && forward.isEmpty() && corrections.isEmpty()
                && !providers.hasFinalEvent(game.gameId())) {
            Instant lastNewPlay = providers.lastNewPlayAt(game.gameId());
            if (lastNewPlay != null && !lastNewPlay.plus(DERIVED_FINAL_GRACE).isAfter(now)) {
                insertForward(List.of(derivedFinal(game, latestMapped)), now);
                derivedFinal = true;
            }
        }

        boolean changed = !forward.isEmpty() || !corrections.isEmpty() || derivedFinal;
        providers.recordPollSuccess(game, plays.size(), changed, now);
        return new FeedResult(forward.size(), corrections.size(), unchanged, rejected, derivedFinal);
    }

    private FixtureGame register(ProviderGame game, Instant now) {
        providers.upsertTeam(game.source(), game.homeTeam(), now);
        providers.upsertTeam(game.source(), game.awayTeam(), now);
        FixtureGame fixtureGame = new FixtureGame(
                game.gameId(), game.homeTeam().teamId(), game.awayTeam().teamId());
        fixtures.ensureProviderGame(game.source(), fixtureGame, game.providerGameId(),
                game.scheduledAt(), now);
        return fixtureGame;
    }

    private void insertForward(List<LoadedSourceEvent> events, Instant now) {
        for (LoadedSourceEvent sourceEvent : events) {
            CanonicalEvent event = sourceEvent.canonicalEvent();
            var raw = fixtures.insertOrObserveRaw(sourceEvent, now);
            fixtures.insertCanonical(event, raw.id(), now);
            outbox.insert(
                    "CANONICAL_EVENT_READY:" + event.eventId(),
                    "CANONICAL_EVENT",
                    event.eventId(),
                    "CANONICAL_EVENT_READY",
                    Map.of("eventId", event.eventId(), "gameId", event.gameId(),
                            "sequence", event.sequence()),
                    now);
        }
    }

    /**
     * Some feeds never publish an explicit end-of-game play. Once the provider reports the game
     * final and nothing new has arrived for the grace period, CourtPulse records a derived final
     * with its own identity and raw evidence describing exactly what it was derived from.
     */
    private static LoadedSourceEvent derivedFinal(ProviderGame game, CanonicalEvent last) {
        String raw = "{\"after_sequence\":" + last.sequence()
                + ",\"derived\":\"GAME_FINAL\",\"provider_game_id\":\"" + game.providerGameId()
                + "\",\"provider_status\":\"" + game.providerStatus().replace("\"", "") + "\"}";
        CanonicalEvent event = new CanonicalEvent(
                game.gameId() + "-final-derived", CanonicalEvent.CURRENT_SCHEMA_VERSION,
                game.gameId(), game.source(), game.providerGameId() + ":final",
                last.sequence() + 1, 1, EventType.GAME_FINAL, last.period(), 0, last.occurredAt(),
                null, List.of(), last.scoreAfter(), 0, "Final (derived from provider game status)");
        return new LoadedSourceEvent(event, raw, sha256(raw));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }

    /** How long, and how often, completed games are re-polled for provider corrections. */
    public record FinalRefetch(Duration window, Duration interval) {
        public static final FinalRefetch DEFAULT = new FinalRefetch(Duration.ofHours(6), Duration.ofMinutes(10));

        public FinalRefetch {
            if (window.isNegative() || interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("final refetch window and interval must be positive");
            }
        }
    }

    public record FeedResult(int newEvents, int corrections, int unchanged, int rejected, boolean derivedFinal) {}
}
