package com.courtpulse.testkit;

import com.courtpulse.providers.fixture.FixtureLoader;
import com.courtpulse.providers.fixture.LoadedFixture;
import java.io.InputStream;

public final class SyntheticFixtureResources {
    public static final String MILESTONE_GAME = "/fixtures/synthetic-milestone-game.json";
    /** Fictional game in the documented BALLDONTLIE v1 response shapes (overtime + one correction). */
    public static final String BALLDONTLIE_OVERTIME_GAME =
            "/fixtures/providers/balldontlie/synthetic-overtime-game.json";
    /** Fictional game in the NBA.com play-by-play CSV layout that replay imports (41 actions, 16-12). */
    public static final String NBA_REPLAY_GAME = "/fixtures/providers/nba/fictional-playoff-game.csv";

    private SyntheticFixtureResources() {}

    public static LoadedFixture loadMilestoneGame() {
        try (InputStream inputStream = SyntheticFixtureResources.class.getResourceAsStream(MILESTONE_GAME)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing classpath fixture: " + MILESTONE_GAME);
            }
            return new FixtureLoader().load(inputStream);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to close fixture resource", exception);
        }
    }

    public static byte[] ballDontLieOvertimeGame() {
        try (InputStream inputStream = SyntheticFixtureResources.class
                .getResourceAsStream(BALLDONTLIE_OVERTIME_GAME)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing classpath fixture: " + BALLDONTLIE_OVERTIME_GAME);
            }
            return inputStream.readAllBytes();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to read fixture resource", exception);
        }
    }

    public static byte[] nbaReplayGame() {
        try (InputStream inputStream = SyntheticFixtureResources.class.getResourceAsStream(NBA_REPLAY_GAME)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing classpath fixture: " + NBA_REPLAY_GAME);
            }
            return inputStream.readAllBytes();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to read fixture resource", exception);
        }
    }
}
