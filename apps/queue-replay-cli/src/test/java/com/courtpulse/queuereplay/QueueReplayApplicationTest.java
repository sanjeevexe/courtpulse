package com.courtpulse.queuereplay;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class QueueReplayApplicationTest {
    @Test
    void helpStartsWithoutDatabaseOrQueueConnections() {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            QueueReplayApplication.main(new String[] {"--help"});
        } finally {
            System.setOut(original);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Usage: queue-replay-cli"));
    }
}
