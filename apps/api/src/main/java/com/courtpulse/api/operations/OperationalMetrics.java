package com.courtpulse.api.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Cached aggregate gauges with a fixed, reviewed label vocabulary. */
public final class OperationalMetrics implements MeterBinder {
    private final JdbcClient jdbc;
    private final Clock clock;
    private volatile Snapshot cached;
    private volatile long expiresAt;

    public OperationalMetrics(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "courtpulse.feed.freshness.seconds", () -> snapshot().feedAge());
        gauge(registry, "courtpulse.feed.live.games", () -> snapshot().liveGames());
        gauge(registry, "courtpulse.event.oldest.unprocessed.seconds",
                () -> snapshot().eventLag());
        gauge(registry, "courtpulse.event.duplicates.suppressed",
                () -> snapshot().duplicates());
        gauge(registry, "courtpulse.outbox.backlog", () -> snapshot().gameOutbox(),
                "destination", "game_events");
        gauge(registry, "courtpulse.outbox.backlog", () -> snapshot().realtimeOutbox(),
                "destination", "realtime");
        gauge(registry, "courtpulse.outbox.oldest.pending.seconds",
                () -> snapshot().gameOutboxAge(), "destination", "game_events");
        gauge(registry, "courtpulse.outbox.oldest.pending.seconds",
                () -> snapshot().realtimeOutboxAge(), "destination", "realtime");
        gauge(registry, "courtpulse.queue.depth", () -> snapshot().gameQueue(),
                "queue", "game_events");
        gauge(registry, "courtpulse.queue.depth", () -> snapshot().gameDlq(),
                "queue", "game_events_dlq");
        gauge(registry, "courtpulse.queue.depth", () -> snapshot().deliveryQueue(),
                "queue", "alert_deliveries");
        gauge(registry, "courtpulse.queue.depth", () -> snapshot().deliveryDlq(),
                "queue", "alert_deliveries_dlq");
        gauge(registry, "courtpulse.queue.observation.age.seconds",
                () -> snapshot().queueObservationAge());
        gauge(registry, "courtpulse.reconciliation.blocked", () -> snapshot().blocked());
        gauge(registry, "courtpulse.reconciliation.pending", () -> snapshot().pending());
        gauge(registry, "courtpulse.reconciliation.completed", () -> snapshot().completed());
        gauge(registry, "courtpulse.worker.heartbeat.age.seconds",
                () -> snapshot().deliveryWorkerAge(), "worker", "delivery");
        gauge(registry, "courtpulse.worker.heartbeat.age.seconds",
                () -> snapshot().reconciliationWorkerAge(), "worker", "reconciliation");
        gauge(registry, "courtpulse.worker.consecutive.failures",
                () -> snapshot().deliveryFailures(), "worker", "delivery");
        gauge(registry, "courtpulse.worker.consecutive.failures",
                () -> snapshot().reconciliationFailures(), "worker", "reconciliation");
    }

    private void gauge(MeterRegistry registry, String name,
            java.util.function.DoubleSupplier value, String... tags) {
        Gauge.builder(name, () -> value.getAsDouble()).tags(tags).register(registry);
    }

    private Snapshot snapshot() {
        long now = System.nanoTime();
        Snapshot result = cached;
        if (result != null && now < expiresAt) return result;
        synchronized (this) {
            now = System.nanoTime();
            if (cached != null && now < expiresAt) return cached;
            result = jdbc.sql("""
                    SELECT
                      COALESCE((SELECT EXTRACT(EPOCH FROM (:now - max(last_observed_at)))
                        FROM raw_provider_payloads), -1) AS feed_age,
                      (SELECT count(*) FROM games WHERE status = 'LIVE') AS live_games,
                      COALESCE((SELECT EXTRACT(EPOCH FROM (:now - min(event.created_at)))
                        FROM canonical_events event
                        WHERE NOT EXISTS (SELECT 1 FROM processed_events processed
                            WHERE processed.event_id = event.event_id)
                          AND NOT EXISTS (SELECT 1 FROM canonical_events newer
                            WHERE newer.game_id = event.game_id
                              AND newer.sequence_number = event.sequence_number
                              AND newer.revision > event.revision)), 0) AS event_lag,
                      COALESCE((SELECT value FROM operational_counters
                        WHERE counter_name = 'game_event_duplicate'), 0) AS duplicates,
                      (SELECT count(*) FROM outbox WHERE destination = 'GAME_EVENTS'
                        AND status IN ('PENDING', 'PUBLISHING', 'RETRY_SCHEDULED')) AS game_outbox,
                      (SELECT count(*) FROM outbox WHERE destination = 'FUTURE_NOTIFICATIONS'
                        AND status IN ('PENDING', 'PUBLISHING', 'RETRY_SCHEDULED')) AS realtime_outbox,
                      COALESCE((SELECT EXTRACT(EPOCH FROM (:now - min(created_at)))
                        FROM outbox WHERE destination = 'GAME_EVENTS'
                        AND status IN ('PENDING', 'PUBLISHING', 'RETRY_SCHEDULED')), 0) AS game_outbox_age,
                      COALESCE((SELECT EXTRACT(EPOCH FROM (:now - min(created_at)))
                        FROM outbox WHERE destination = 'FUTURE_NOTIFICATIONS'
                        AND status IN ('PENDING', 'PUBLISHING', 'RETRY_SCHEDULED')), 0) AS realtime_outbox_age,
                      COALESCE((SELECT visible + in_flight + delayed FROM queue_observations
                        WHERE queue_type = 'game_events'), 0) AS game_queue,
                      COALESCE((SELECT visible + in_flight + delayed FROM queue_observations
                        WHERE queue_type = 'game_events_dlq'), 0) AS game_dlq,
                      COALESCE((SELECT visible + in_flight + delayed FROM queue_observations
                        WHERE queue_type = 'alert_deliveries'), 0) AS delivery_queue,
                      COALESCE((SELECT visible + in_flight + delayed FROM queue_observations
                        WHERE queue_type = 'alert_deliveries_dlq'), 0) AS delivery_dlq,
                      COALESCE((SELECT EXTRACT(EPOCH FROM (:now - max(observed_at)))
                        FROM queue_observations), -1) AS queue_observation_age,
                      (SELECT count(*) FROM game_reconciliations WHERE status = 'BLOCKED') AS blocked,
                      (SELECT count(*) FROM game_reconciliations WHERE status IN
                        ('PENDING', 'REBUILDING')) AS pending,
                      (SELECT count(*) FROM reconciliation_attempts WHERE status IN
                        ('COMPLETED', 'UNCHANGED')) AS completed,
                      COALESCE((SELECT EXTRACT(EPOCH FROM (:now - observed_at))
                        FROM worker_heartbeats WHERE worker_type = 'delivery'), -1) AS delivery_worker_age,
                      COALESCE((SELECT EXTRACT(EPOCH FROM (:now - observed_at))
                        FROM worker_heartbeats WHERE worker_type = 'reconciliation'), -1) AS reconciliation_worker_age,
                      COALESCE((SELECT consecutive_failures FROM worker_heartbeats
                        WHERE worker_type = 'delivery'), 0) AS delivery_failures,
                      COALESCE((SELECT consecutive_failures FROM worker_heartbeats
                        WHERE worker_type = 'reconciliation'), 0) AS reconciliation_failures
                    """).param("now", java.time.OffsetDateTime.ofInstant(clock.instant(),
                            java.time.ZoneOffset.UTC))
                    .query((row, n) -> new Snapshot(
                            row.getDouble("feed_age"), row.getDouble("live_games"),
                            row.getDouble("event_lag"), row.getDouble("duplicates"),
                            row.getDouble("game_outbox"), row.getDouble("realtime_outbox"),
                            row.getDouble("game_outbox_age"), row.getDouble("realtime_outbox_age"),
                            row.getDouble("game_queue"), row.getDouble("game_dlq"),
                            row.getDouble("delivery_queue"), row.getDouble("delivery_dlq"),
                            row.getDouble("queue_observation_age"), row.getDouble("blocked"),
                            row.getDouble("pending"), row.getDouble("completed"),
                            row.getDouble("delivery_worker_age"),
                            row.getDouble("reconciliation_worker_age"),
                            row.getDouble("delivery_failures"),
                            row.getDouble("reconciliation_failures"))).single();
            cached = result;
            expiresAt = now + java.time.Duration.ofSeconds(5).toNanos();
            return result;
        }
    }

    private record Snapshot(double feedAge, double liveGames, double eventLag,
            double duplicates, double gameOutbox,
            double realtimeOutbox, double gameOutboxAge, double realtimeOutboxAge,
            double gameQueue, double gameDlq, double deliveryQueue, double deliveryDlq,
            double queueObservationAge, double blocked, double pending, double completed,
            double deliveryWorkerAge, double reconciliationWorkerAge,
            double deliveryFailures, double reconciliationFailures) {}
}
