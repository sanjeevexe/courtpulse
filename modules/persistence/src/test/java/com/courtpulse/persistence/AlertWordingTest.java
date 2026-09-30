package com.courtpulse.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.courtpulse.domain.alert.RuleType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertWordingTest {
    private final JdbcDisplayNames.Names names = new JdbcDisplayNames.Names(
            Map.of("bdl-player-9000203", "Hal Quill"), Map.of("bdl-team-90001", "Harbor City Herons"),
            Map.of("bdl-game-990001", "Summit Valley Sentinels at Harbor City Herons"));

    @Test
    void usesNamesForPlayersAndTeams() {
        assertEquals("Hal Quill reached 16 points", AlertWording.title(RuleType.PLAYER_POINTS,
                Map.of("playerId", "bdl-player-9000203", "threshold", "16", "verifiedTotal", "16"), "stored", names));
        assertEquals("Harbor City Herons went on a 9-0 run", AlertWording.title(RuleType.SCORING_RUN,
                Map.of("teamId", "bdl-team-90001", "threshold", "7", "verifiedRunPoints", "9"), "stored", names));
        assertEquals("Summit Valley Sentinels at Harbor City Herons", names.game("bdl-game-990001"));
    }

    @Test
    void describesCloseGamesInBasketballTerms() {
        assertEquals("3-point game with 0:06 left in Q4", AlertWording.title(RuleType.CLOSE_GAME,
                Map.of("verifiedMargin", "3", "clockMillisRemaining", "6000", "period", "4"), "stored", names));
        assertEquals("Tied with 1:55 left in OT", AlertWording.title(RuleType.CLOSE_GAME,
                Map.of("verifiedMargin", "0", "clockMillisRemaining", "115000", "period", "5"), "stored", names));
        assertEquals("2OT", AlertWording.period(6));
    }

    @Test
    void fallsBackToIdsOrTheStoredTitle() {
        assertEquals("bdl-player-1 reached 10 points", AlertWording.title(RuleType.PLAYER_POINTS,
                Map.of("playerId", "bdl-player-1", "threshold", "10"), "stored", names));
        assertEquals("stored", AlertWording.title(RuleType.CLOSE_GAME, Map.of(), "stored", names));
        assertEquals("game-x", names.game("game-x"));
    }
}
