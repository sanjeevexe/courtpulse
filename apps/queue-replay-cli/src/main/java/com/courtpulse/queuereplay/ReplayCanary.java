package com.courtpulse.queuereplay;

import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.replay.StateChecksum;
import com.courtpulse.persistence.FixtureIngestionService;
import com.courtpulse.persistence.JdbcGameProcessingRepository;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.testkit.SyntheticFixtureResources;
import java.time.Duration;
import java.time.Instant;

/**
 * Post-deployment canary: imports the redistributable synthetic game idempotently, then waits for
 * the deployed processor (not this process) to reach the known final checksum. It proves the
 * outbox, queue, consumer, and database path end to end without resetting any data.
 */
final class ReplayCanary {
    static final String EXPECTED_CHECKSUM = "06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca";
    static final long EXPECTED_VERSION = 20;

    private final FixtureIngestionService ingestion;
    private final JdbcGameProcessingRepository processing;
    private final Duration timeout;

    ReplayCanary(FixtureIngestionService ingestion, JdbcGameProcessingRepository processing, Duration timeout) {
        this.ingestion = ingestion;
        this.processing = processing;
        this.timeout = timeout;
    }

    void run() {
        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        var imported = ingestion.importFixture(fixture);
        System.out.printf("Canary import: raw=%d canonical=%d outbox=%d (zero means already present)%n",
                imported.insertedRawPayloads(), imported.insertedCanonicalEvents(),
                imported.insertedOutboxRecords());
        Instant deadline = Instant.now().plus(timeout);
        String gameId = fixture.game().gameId();
        while (true) {
            GameState state = processing.readCheckpoint(gameId);
            String checksum = StateChecksum.sha256(state);
            if (state.lastAppliedSequence() == EXPECTED_VERSION && EXPECTED_CHECKSUM.equals(checksum)) {
                System.out.printf("Canary passed: %s sequence=%d checksum=%s%n",
                        gameId, state.lastAppliedSequence(), checksum);
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Canary timed out at sequence " + state.lastAppliedSequence()
                        + " with checksum " + checksum + "; is the processor service running?");
            }
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Canary interrupted", exception);
            }
        }
    }
}
