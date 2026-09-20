package com.courtpulse.persistence;

import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.replay.StateChecksum;
import com.courtpulse.providers.fixture.LoadedFixture;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DurableReplayCoordinator {
    private final FixtureIngestionService ingestion;
    private final JdbcFixtureRepository fixtures;
    private final JdbcGameProcessingRepository processingRepository;
    private final DurableGameProcessor processor;
    private final JdbcInspectionRepository inspection;
    private final JdbcReplayRunRepository replayRuns;
    private final Clock clock;

    public DurableReplayCoordinator(
            FixtureIngestionService ingestion,
            JdbcFixtureRepository fixtures,
            JdbcGameProcessingRepository processingRepository,
            DurableGameProcessor processor,
            JdbcInspectionRepository inspection,
            JdbcReplayRunRepository replayRuns,
            Clock clock) {
        this.ingestion = ingestion;
        this.fixtures = fixtures;
        this.processingRepository = processingRepository;
        this.processor = processor;
        this.inspection = inspection;
        this.replayRuns = replayRuns;
        this.clock = clock;
    }

    public void reset() {
        fixtures.resetAll();
    }

    public DurableReplayResult execute(
            LoadedFixture fixture,
            boolean importFixture,
            boolean injectDuplicates) {
        ImportResult importResult = importFixture
                ? ingestion.importFixture(fixture)
                : new ImportResult(0, 0, 0);
        String mode = injectDuplicates ? "DUPLICATE_INJECTION" : "NORMAL";
        UUID runId = replayRuns.start(fixture.name(), mode, clock.instant());
        long accepted = 0;
        long suppressed = 0;

        try {
            List<String> eventIds = new ArrayList<>(
                    processingRepository.listEventIds(fixture.game().gameId()));
            if (injectDuplicates) {
                eventIds = withDuplicates(eventIds, fixture.events());
            }
            for (String eventId : eventIds) {
                DurableProcessingResult result = processor.processEvent(eventId);
                if (result.accepted()) {
                    accepted++;
                } else {
                    suppressed++;
                }
            }

            GameState finalState = processingRepository.readCheckpoint(fixture.game().gameId());
            String checksum = StateChecksum.sha256(finalState);
            replayRuns.complete(runId, accepted, suppressed, checksum, clock.instant());
            return new DurableReplayResult(
                    importResult,
                    finalState,
                    processingRepository.listAlerts(fixture.game().gameId()),
                    accepted,
                    suppressed,
                    checksum,
                    inspection.counts());
        } catch (RuntimeException exception) {
            replayRuns.fail(runId, clock.instant());
            throw exception;
        }
    }

    private static List<String> withDuplicates(
            List<String> orderedEventIds, List<CanonicalEvent> events) {
        java.util.Map<String, Long> sequences = events.stream()
                .collect(java.util.stream.Collectors.toMap(CanonicalEvent::eventId, CanonicalEvent::sequence));
        List<String> duplicated = new ArrayList<>();
        for (String eventId : orderedEventIds) {
            duplicated.add(eventId);
            long sequence = sequences.get(eventId);
            if (sequence == 4 || sequence == 11) {
                duplicated.add(eventId);
            }
        }
        return List.copyOf(duplicated);
    }
}
