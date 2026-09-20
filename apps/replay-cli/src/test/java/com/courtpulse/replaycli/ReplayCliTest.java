package com.courtpulse.replaycli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReplayCliTest {
    @Test
    void duplicateModePrintsMilestoneAndSuppressionEvidence() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        int exitCode = ReplayCli.run(new String[] {"--inject-duplicates"}, output, output);
        String text = bytes.toString(StandardCharsets.UTF_8);

        assertEquals(0, exitCode);
        assertTrue(text.contains("Final score: HOME 18 - AWAY 14"));
        assertTrue(text.contains("Selected player points: player_ace = 13"));
        assertTrue(text.contains("Accepted events: 20"));
        assertTrue(text.contains("Suppressed duplicates: 2"));
        assertTrue(text.contains("Alerts: 1"));
        assertTrue(text.contains("PLAYER_MILESTONE:milestone-player-ace-10:game_synthetic_001:POINTS:10"));
        assertTrue(text.contains("Final-state checksum:"));
    }
}
