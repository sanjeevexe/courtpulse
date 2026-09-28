package com.courtpulse.persistence;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.providers.fixture.LoadedSourceEvent;
import com.courtpulse.observability.TraceContext;
import io.opentelemetry.api.trace.SpanKind;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.transaction.support.TransactionTemplate;

public final class FixtureIngestionService {
    private final JdbcFixtureRepository fixtures;
    private final JdbcOutboxRepository outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public FixtureIngestionService(
            JdbcFixtureRepository fixtures,
            JdbcOutboxRepository outbox,
            TransactionTemplate transactions,
            Clock clock) {
        this.fixtures = fixtures;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public ImportResult importFixture(LoadedFixture fixture) {
        try (var span = TraceContext.start("fixture ingest", SpanKind.INTERNAL)) {
            span.span().setAttribute("courtpulse.source.events", fixture.sourceEvents().size());
            return transactions.execute(status -> importInTransaction(fixture));
        }
    }

    private ImportResult importInTransaction(LoadedFixture fixture) {
        Instant now = clock.instant();
        String source = fixture.sourceEvents().getFirst().canonicalEvent().source();
        fixtures.ensureGame(source, fixture.game(), now);
        long rawInserted = 0;
        long canonicalInserted = 0;
        long outboxInserted = 0;

        for (LoadedSourceEvent sourceEvent : fixture.sourceEvents()) {
            CanonicalEvent event = sourceEvent.canonicalEvent();
            JdbcFixtureRepository.RawPayloadInsert raw = fixtures.insertOrObserveRaw(sourceEvent, now);
            if (raw.inserted()) {
                rawInserted++;
            }
            if (fixtures.insertCanonical(event, raw.id(), now)) {
                canonicalInserted++;
            }
            if (outbox.insert(
                    "CANONICAL_EVENT_READY:" + event.eventId(),
                    "CANONICAL_EVENT",
                    event.eventId(),
                    "CANONICAL_EVENT_READY",
                    Map.of(
                            "eventId", event.eventId(),
                            "gameId", event.gameId(),
                            "sequence", event.sequence()),
                    now)) {
                outboxInserted++;
            }
        }
        return new ImportResult(rawInserted, canonicalInserted, outboxInserted);
    }
}
