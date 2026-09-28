package com.courtpulse.persistence;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Fixed-label aggregate observations only. No payloads or personal data are stored. */
public final class JdbcOperationalTelemetryRepository {
    public static final java.util.Set<String> WORKER_TYPES =
            java.util.Set.of("delivery", "reconciliation", "processor", "ingestor");
    private final JdbcClient jdbc;

    public JdbcOperationalTelemetryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void heartbeat(String workerType, Instant now, boolean successful) {
        requireWorkerType(workerType);
        jdbc.sql("""
                INSERT INTO worker_heartbeats(worker_type, observed_at, last_success_at,
                    consecutive_failures)
                VALUES (:type, :now, :successAt, :failures)
                ON CONFLICT (worker_type) DO UPDATE SET
                    observed_at = EXCLUDED.observed_at,
                    last_success_at = COALESCE(EXCLUDED.last_success_at,
                        worker_heartbeats.last_success_at),
                    consecutive_failures = CASE WHEN :success THEN 0
                        ELSE worker_heartbeats.consecutive_failures + 1 END
                """).param("type", workerType).param("now", SqlTime.offset(now))
                .param("successAt", successful ? SqlTime.offset(now) : null)
                .param("failures", successful ? 0 : 1)
                .param("success", successful).update();
    }

    public void queue(String queueType, long visible, long inFlight, long delayed, Instant now) {
        requireQueueType(queueType);
        if (visible < 0 || inFlight < 0 || delayed < 0) {
            throw new IllegalArgumentException("queue depths must be nonnegative");
        }
        jdbc.sql("""
                INSERT INTO queue_observations(queue_type, visible, in_flight, delayed, observed_at)
                VALUES (:type, :visible, :inFlight, :delayed, :now)
                ON CONFLICT (queue_type) DO UPDATE SET
                    visible = EXCLUDED.visible, in_flight = EXCLUDED.in_flight,
                    delayed = EXCLUDED.delayed, observed_at = EXCLUDED.observed_at
                """).param("type", queueType).param("visible", visible)
                .param("inFlight", inFlight).param("delayed", delayed)
                .param("now", SqlTime.offset(now)).update();
    }

    public void duplicateSuppressed() {
        jdbc.sql("""
                INSERT INTO operational_counters(counter_name, value)
                VALUES ('game_event_duplicate', 1)
                ON CONFLICT (counter_name) DO UPDATE
                SET value = operational_counters.value + 1
                """).update();
    }

    private static void requireWorkerType(String value) {
        if (!WORKER_TYPES.contains(value)) {
            throw new IllegalArgumentException("unknown worker type");
        }
    }

    private static void requireQueueType(String value) {
        if (!"game_events".equals(value) && !"game_events_dlq".equals(value)
                && !"alert_deliveries".equals(value)
                && !"alert_deliveries_dlq".equals(value)) {
            throw new IllegalArgumentException("unknown queue type");
        }
    }
}
