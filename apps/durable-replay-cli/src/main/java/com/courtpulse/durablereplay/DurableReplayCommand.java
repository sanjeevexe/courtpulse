package com.courtpulse.durablereplay;

import com.courtpulse.persistence.DatabaseCounts;
import com.courtpulse.persistence.DurableReplayCoordinator;
import com.courtpulse.persistence.DurableReplayResult;
import com.courtpulse.persistence.StoredAlert;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.testkit.SyntheticFixtureResources;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public final class DurableReplayCommand implements ApplicationRunner {
    private static final Set<String> SUPPORTED =
            Set.of("reset", "inject-duplicates", "reprocess-existing", "help");

    private final DurableReplayCoordinator coordinator;

    public DurableReplayCommand(DurableReplayCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        List<String> unknownOptions = arguments.getOptionNames().stream()
                .filter(option -> !SUPPORTED.contains(option))
                .filter(option -> !option.startsWith("spring."))
                .filter(option -> !option.startsWith("logging."))
                .sorted()
                .toList();
        if (!unknownOptions.isEmpty() || !arguments.getNonOptionArgs().isEmpty()) {
            throw new IllegalArgumentException(
                    "Unsupported arguments. Usage: durable-replay-cli [--reset] "
                            + "[--inject-duplicates] [--reprocess-existing]");
        }
        if (arguments.containsOption("help")) {
            System.out.println(
                    "Usage: durable-replay-cli [--reset] [--inject-duplicates] [--reprocess-existing]");
            return;
        }
        if (arguments.containsOption("reset") && arguments.containsOption("reprocess-existing")) {
            throw new IllegalArgumentException("--reset cannot be combined with --reprocess-existing");
        }

        boolean reset = arguments.containsOption("reset");
        boolean injectDuplicates = arguments.containsOption("inject-duplicates");
        boolean reprocessExisting = arguments.containsOption("reprocess-existing");
        if (reset) {
            coordinator.reset();
        }

        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        DurableReplayResult result = coordinator.execute(
                fixture,
                !reprocessExisting,
                injectDuplicates);
        printResult(result, reset, reprocessExisting, injectDuplicates);
    }

    private static void printResult(
            DurableReplayResult result,
            boolean reset,
            boolean reprocessExisting,
            boolean injectDuplicates) {
        String mode = reprocessExisting
                ? "reprocess existing"
                : injectDuplicates ? "duplicate injection" : "normal";
        System.out.println("CourtPulse durable PostgreSQL replay");
        System.out.println("Mode: " + mode + (reset ? " after reset" : ""));
        System.out.println("Game ID: " + result.finalState().gameId());
        System.out.println("Status: " + result.finalState().status());
        System.out.printf(
                "Final score: HOME %d - AWAY %d%n",
                result.finalState().homeScore(), result.finalState().awayScore());
        System.out.printf(
                "Selected player points: player_ace = %d%n",
                result.finalState().pointsFor("player_ace"));
        System.out.println("Accepted events this run: " + result.acceptedEventCount());
        System.out.println("Suppressed duplicates this run: " + result.suppressedDuplicateCount());
        System.out.printf(
                "Imported this run: raw=%d canonical=%d outbox=%d%n",
                result.importResult().insertedRawPayloads(),
                result.importResult().insertedCanonicalEvents(),
                result.importResult().insertedOutboxRecords());
        System.out.println("Stored alerts: " + result.alerts().size());
        for (StoredAlert alert : result.alerts()) {
            System.out.println("- " + alert.triggerKey() + " | " + alert.title());
        }
        System.out.println("Final-state checksum: " + result.finalStateChecksum());
        printCounts(result.databaseCounts());
    }

    private static void printCounts(DatabaseCounts counts) {
        System.out.printf(
                "Table counts: games=%d raw_provider_payloads=%d canonical_events=%d "
                        + "game_checkpoints=%d processed_events=%d alert_instances=%d "
                        + "outbox=%d replay_runs=%d%n",
                counts.games(),
                counts.rawProviderPayloads(),
                counts.canonicalEvents(),
                counts.gameCheckpoints(),
                counts.processedEvents(),
                counts.alertInstances(),
                counts.outboxRecords(),
                counts.replayRuns());
    }
}
