package com.courtpulse.queuereplay;

import com.courtpulse.messaging.consumer.GameEventQueueConsumer;
import com.courtpulse.messaging.publisher.OutboxPublisher;
import com.courtpulse.messaging.publisher.PublisherFailureMode;
import com.courtpulse.messaging.queue.QueueDepth;
import com.courtpulse.messaging.queue.QueuePort;
import com.courtpulse.persistence.JdbcOperationalTelemetryRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Continuous game-event pipeline for deployed environments: one thread publishes committed
 * outbox rows (short idle sleep, so publish latency stays low while consumers long-poll) and N
 * threads consume the FIFO queue. The main thread reports heartbeat and queue depth and stops the
 * process if a worker thread dies, so the orchestrator restarts it instead of running half a
 * pipeline.
 */
final class ProcessorDaemon {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessorDaemon.class);
    private static final Duration PUBLISH_IDLE = Duration.ofMillis(100);
    private static final int MAXIMUM_CONSECUTIVE_FAILURES = 5;
    private static final Path HEARTBEAT = Path.of("/tmp/courtpulse-processor-worker.heartbeat");

    private final OutboxPublisher publisher;
    private final GameEventQueueConsumer consumer;
    private final QueuePort queue;
    private final QueuePort dlq;
    private final JdbcOperationalTelemetryRepository telemetry;
    private final int consumerThreads;

    ProcessorDaemon(OutboxPublisher publisher, GameEventQueueConsumer consumer, QueuePort queue,
            QueuePort dlq, JdbcOperationalTelemetryRepository telemetry, int consumerThreads) {
        this.publisher = publisher;
        this.consumer = consumer;
        this.queue = queue;
        this.dlq = dlq;
        this.telemetry = telemetry;
        this.consumerThreads = consumerThreads;
    }

    void run() {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        workers.add(worker("outbox-publisher", running, failure, () -> {
            if (publisher.publishBatch(PublisherFailureMode.NONE).claimed() == 0) {
                sleep(PUBLISH_IDLE);
            }
        }));
        for (int index = 0; index < consumerThreads; index++) {
            workers.add(worker("queue-consumer-" + index, running, failure, consumer::pollOnce));
        }
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            main.interrupt();
        }, "processor-shutdown"));
        workers.forEach(Thread::start);
        try {
            while (running.get() && failure.get() == null) {
                observe();
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            running.set(false);
            for (Thread worker : workers) {
                worker.interrupt();
                try {
                    worker.join(10_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (failure.get() != null) {
            telemetry.heartbeat("processor", Instant.now(), false);
            throw new IllegalStateException("Processor worker thread failed", failure.get());
        }
    }

    private void observe() {
        QueueDepth depth = queue.depth();
        QueueDepth dead = dlq.depth();
        Instant now = Instant.now();
        telemetry.queue("game_events", depth.visible(), depth.inFlight(), depth.delayed(), now);
        telemetry.queue("game_events_dlq", dead.visible(), dead.inFlight(), dead.delayed(), now);
        telemetry.heartbeat("processor", now, true);
        try {
            Files.writeString(HEARTBEAT, now.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Processor heartbeat failed", exception);
        }
    }

    private static Thread worker(String name, AtomicBoolean running, AtomicReference<Throwable> failure,
            Runnable step) {
        Thread thread = new Thread(() -> {
            int consecutiveFailures = 0;
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    step.run();
                    consecutiveFailures = 0;
                } catch (RuntimeException exception) {
                    if (!running.get()) {
                        return;
                    }
                    consecutiveFailures++;
                    LOGGER.atWarn().addKeyValue("thread", name)
                            .addKeyValue("errorType", exception.getClass().getSimpleName())
                            .addKeyValue("consecutiveFailures", consecutiveFailures)
                            .log("Processor worker step failed");
                    if (consecutiveFailures >= MAXIMUM_CONSECUTIVE_FAILURES) {
                        failure.compareAndSet(null, exception);
                        return;
                    }
                    sleep(Duration.ofSeconds(consecutiveFailures));
                }
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
