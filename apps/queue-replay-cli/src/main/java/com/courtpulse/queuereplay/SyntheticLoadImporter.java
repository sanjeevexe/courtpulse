package com.courtpulse.queuereplay;

import com.courtpulse.persistence.FixtureIngestionService;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.testkit.SyntheticLoadGames;
import java.time.Duration;
import java.util.List;

/**
 * Load-test ingestion. Burst mode commits each synthetic game in one transaction as fast as
 * possible; paced mode starts a round (one event per game) every pace interval at a fixed rate, so
 * 50 games at 250 ms is a sustained 200 events per second. The deployed processor does the rest.
 */
final class SyntheticLoadImporter {
    private final FixtureIngestionService ingestion;

    SyntheticLoadImporter(FixtureIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    void run(String prefix, int games, int eventsPerGame, long seed, long paceMillis) {
        if (paceMillis < 0 || paceMillis > 10_000) {
            throw new IllegalArgumentException("--synthetic-pace-ms must be between 0 and 10000");
        }
        List<LoadedFixture> fixtures = SyntheticLoadGames.generate(prefix, games, eventsPerGame, seed);
        long started = System.nanoTime();
        long imported = 0;
        if (paceMillis == 0) {
            for (LoadedFixture fixture : fixtures) {
                imported += ingestion.importFixture(fixture).insertedCanonicalEvents();
            }
        } else {
            long interval = Duration.ofMillis(paceMillis).toNanos();
            for (int sequence = 0; sequence < eventsPerGame; sequence++) {
                for (LoadedFixture fixture : fixtures) {
                    imported += ingestion.importFixture(new LoadedFixture(fixture.fixtureSchemaVersion(),
                            fixture.name(), fixture.description(), fixture.provenance(), fixture.game(),
                            List.of(fixture.sourceEvents().get(sequence)))).insertedCanonicalEvents();
                }
                try {
                    // Fixed rate: the next round starts on schedule however long this one took.
                    long remaining = started + (sequence + 1) * interval - System.nanoTime();
                    if (remaining > 0) {
                        Thread.sleep(Duration.ofNanos(remaining));
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Paced synthetic import interrupted", exception);
                }
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        System.out.printf("Synthetic load imported: games=%d events=%d elapsedMillis=%d mode=%s%n",
                games, imported, elapsed.toMillis(), paceMillis == 0 ? "burst" : "paced-" + paceMillis + "ms");
    }
}
