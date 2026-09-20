package com.courtpulse.replaycli;

import com.courtpulse.domain.alert.Alert;
import com.courtpulse.domain.alert.PlayerMilestoneRule;
import com.courtpulse.domain.event.CanonicalEvent;
import com.courtpulse.domain.replay.ReplayEngine;
import com.courtpulse.domain.replay.ReplayResult;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.testkit.SyntheticFixtureResources;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public final class ReplayCli {
    private static final String SELECTED_PLAYER_ID = "player_ace";
    private static final int MILESTONE_THRESHOLD = 10;

    private ReplayCli() {}

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        boolean injectDuplicates;
        if (args.length == 0) {
            injectDuplicates = false;
        } else if (args.length == 1 && "--inject-duplicates".equals(args[0])) {
            injectDuplicates = true;
        } else if (args.length == 1 && "--help".equals(args[0])) {
            out.println("Usage: replay-cli [--inject-duplicates]");
            return 0;
        } else {
            err.println("Unknown arguments. Usage: replay-cli [--inject-duplicates]");
            return 2;
        }

        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        List<CanonicalEvent> events = injectDuplicates
                ? withDuplicateDeliveries(fixture.events())
                : fixture.events();

        PlayerMilestoneRule rule =
                new PlayerMilestoneRule("milestone-player-ace-10", SELECTED_PLAYER_ID, MILESTONE_THRESHOLD);
        ReplayResult result = new ReplayEngine().replay(
                fixture.game().gameId(),
                fixture.game().homeTeamId(),
                fixture.game().awayTeamId(),
                events,
                List.of(rule));

        out.println("CourtPulse deterministic replay");
        out.println("Fixture: " + fixture.name());
        out.println("Mode: " + (injectDuplicates ? "duplicate injection" : "normal"));
        out.println("Game ID: " + result.finalState().gameId());
        out.println("Status: " + result.finalState().status());
        out.printf("Final score: HOME %d - AWAY %d%n",
                result.finalState().homeScore(), result.finalState().awayScore());
        out.printf("Selected player points: %s = %d%n",
                SELECTED_PLAYER_ID, result.finalState().pointsFor(SELECTED_PLAYER_ID));
        out.println("Last applied sequence: " + result.finalState().lastAppliedSequence());
        out.println("Accepted events: " + result.acceptedEventCount());
        out.println("Suppressed duplicates: " + result.suppressedDuplicateCount());
        out.println("Alerts: " + result.alerts().size());
        for (Alert alert : result.alerts()) {
            out.println("- " + alert.triggerKey() + " | " + alert.title());
        }
        out.println("Final-state checksum: " + result.finalStateChecksum());
        return 0;
    }

    private static List<CanonicalEvent> withDuplicateDeliveries(List<CanonicalEvent> source) {
        List<CanonicalEvent> duplicated = new ArrayList<>();
        for (CanonicalEvent event : source) {
            duplicated.add(event);
            if (event.sequence() == 4 || event.sequence() == 11) {
                duplicated.add(event);
            }
        }
        return List.copyOf(duplicated);
    }
}
