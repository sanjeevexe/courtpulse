package com.courtpulse.queuereplay;

import com.courtpulse.domain.game.GameState;
import com.courtpulse.domain.replay.StateChecksum;
import com.courtpulse.messaging.consumer.ConsumerBatchResult;
import com.courtpulse.messaging.consumer.ConsumerFailureMode;
import com.courtpulse.messaging.consumer.GameEventQueueConsumer;
import com.courtpulse.messaging.consumer.SimulatedConsumerCrashException;
import com.courtpulse.messaging.publisher.OutboxPublisher;
import com.courtpulse.messaging.publisher.PublisherBatchResult;
import com.courtpulse.messaging.publisher.PublisherFailureMode;
import com.courtpulse.messaging.publisher.SimulatedPublisherCrashException;
import com.courtpulse.messaging.queue.QueueDepth;
import com.courtpulse.messaging.queue.QueuePort;
import com.courtpulse.messaging.delivery.DeliveryQueuePublisher;
import com.courtpulse.messaging.delivery.DeliveryQueueConsumer;
import com.courtpulse.persistence.DatabaseCounts;
import com.courtpulse.persistence.FixtureIngestionService;
import com.courtpulse.persistence.GameReconciliationService;
import com.courtpulse.persistence.ImportResult;
import com.courtpulse.persistence.JdbcFixtureRepository;
import com.courtpulse.persistence.JdbcGameProcessingRepository;
import com.courtpulse.persistence.JdbcDeliveryWorkRepository;
import com.courtpulse.persistence.JdbcOperationalTelemetryRepository;
import com.courtpulse.persistence.JdbcInspectionRepository;
import com.courtpulse.persistence.JdbcOutboxPublicationRepository;
import com.courtpulse.persistence.OutboxStatusCounts;
import com.courtpulse.providers.fixture.LoadedFixture;
import com.courtpulse.providers.fixture.FixtureLoader;
import com.courtpulse.testkit.SyntheticFixtureResources;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;

@Component
public final class QueueReplayCommand implements ApplicationRunner {
    private static final Set<String> SUPPORTED = Set.of(
            "reset-import", "publish", "drain", "run", "inspect", "help",
            "pace-ms",
            "correction-fixture", "reconcile-game", "reconciliation-run", "reconciliation-daemon",
            "simulate-publisher-after-send", "simulate-consumer-before-commit",
            "simulate-consumer-after-commit");
    private static final Set<String> DELIVERY_OPTIONS = Set.of(
            "delivery-publish", "delivery-drain", "delivery-run", "delivery-daemon");

    private final JdbcFixtureRepository fixtures;
    private final FixtureIngestionService ingestion;
    private final GameReconciliationService reconciliation;
    private final OutboxPublisher publisher;
    private final GameEventQueueConsumer consumer;
    private final QueuePort queue;
    private final QueuePort dlq;
    private final QueuePort deliveryQueue;
    private final QueuePort deliveryDlq;
    private final DeliveryQueuePublisher deliveryPublisher;
    private final DeliveryQueueConsumer deliveryConsumer;
    private final JdbcDeliveryWorkRepository deliveryWork;
    private final JdbcOperationalTelemetryRepository operationalTelemetry;
    private final JdbcOutboxPublicationRepository outbox;
    private final JdbcInspectionRepository inspection;
    private final JdbcGameProcessingRepository processing;
    private final SqsClient sqs;
    private final String queueUrl;
    private final String dlqUrl;
    private final Duration drainTimeout;
    private final int workerConcurrency;

    public QueueReplayCommand(
            JdbcFixtureRepository fixtures,
            FixtureIngestionService ingestion,
            GameReconciliationService reconciliation,
            OutboxPublisher publisher,
            GameEventQueueConsumer consumer,
            @Qualifier("gameEventsQueue") QueuePort queue,
            @Qualifier("gameEventsDlq") QueuePort dlq,
            @Qualifier("alertDeliveriesQueue") QueuePort deliveryQueue,
            @Qualifier("alertDeliveriesDlq") QueuePort deliveryDlq,
            DeliveryQueuePublisher deliveryPublisher,
            DeliveryQueueConsumer deliveryConsumer,
            JdbcDeliveryWorkRepository deliveryWork,
            JdbcOperationalTelemetryRepository operationalTelemetry,
            JdbcOutboxPublicationRepository outbox,
            JdbcInspectionRepository inspection,
            JdbcGameProcessingRepository processing,
            SqsClient sqs,
            @Qualifier("gameEventsQueueUrl") String queueUrl,
            @Qualifier("gameEventsDlqUrl") String dlqUrl,
            @Value("${courtpulse.demo.drain-timeout}") Duration drainTimeout,
            @Value("${courtpulse.consumer.worker-concurrency}") int workerConcurrency) {
        this.fixtures = fixtures;
        this.ingestion = ingestion;
        this.reconciliation = reconciliation;
        this.publisher = publisher;
        this.consumer = consumer;
        this.queue = queue;
        this.dlq = dlq;
        this.deliveryQueue = deliveryQueue;
        this.deliveryDlq = deliveryDlq;
        this.deliveryPublisher = deliveryPublisher;
        this.deliveryConsumer = deliveryConsumer;
        this.deliveryWork = deliveryWork;
        this.operationalTelemetry = operationalTelemetry;
        this.outbox = outbox;
        this.inspection = inspection;
        this.processing = processing;
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.dlqUrl = dlqUrl;
        this.drainTimeout = drainTimeout;
        if (workerConcurrency < 1 || workerConcurrency > 8) {
            throw new IllegalArgumentException("consumer worker concurrency must be between 1 and 8");
        }
        this.workerConcurrency = workerConcurrency;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        Options options = Options.parse(arguments);
        if (options.help()) {
            printUsage();
            return;
        }
        if (options.reconciliationDaemon()) {
            runReconciliationDaemon();
            return;
        }
        if (options.correctionFixture() != null || options.reconcileGame() != null
                || options.reconciliationRun()) {
            if (options.correctionFixture() != null) {
                try (var input = Files.newInputStream(Path.of(options.correctionFixture()))) {
                    var submitted = reconciliation.submit(new FixtureLoader().load(input));
                    System.out.printf("Correction submitted: accepted=%d duplicates=%d conflicts=%d%n",
                            submitted.accepted(), submitted.duplicates(), submitted.conflicts());
                } catch (IOException exception) {
                    throw new IllegalArgumentException("Cannot read correction fixture", exception);
                }
            }
            if (options.reconcileGame() != null) {
                var result = reconciliation.reconcile(options.reconcileGame());
                System.out.printf("Reconciliation: status=%s selected=%d checksum=%s error=%s%n",
                        result.status(), result.selectedEvents(), result.checksum(), result.errorCode());
            }
            if (options.reconciliationRun()) {
                var results = reconciliation.reconcileAvailable(50);
                System.out.printf("Reconciliation pass: considered=%d completed=%d blocked=%d%n",
                        results.size(), results.stream().filter(r -> "COMPLETED".equals(r.status())).count(),
                        results.stream().filter(r -> "BLOCKED".equals(r.status())).count());
            }
            return;
        }
        if (options.deliveryDaemon()) {
            runDeliveryDaemon();
            return;
        }
        if (options.deliveryPublish() || options.deliveryDrain() || options.deliveryRun()) {
            runDeliveries(options);
            return;
        }

        LoadedFixture fixture = SyntheticFixtureResources.loadMilestoneGame();
        ImportResult imported = new ImportResult(0, 0, 0);
        if (options.resetImport()) {
            fixtures.resetAll();
            sqs.purgeQueue(builder -> builder.queueUrl(queueUrl));
            sqs.purgeQueue(builder -> builder.queueUrl(dlqUrl));
            imported = ingestion.importFixture(fixture);
        }

        PublisherBatchResult publication = PublisherBatchResult.empty();
        ConsumerBatchResult consumption = ConsumerBatchResult.empty();
        Instant deadline = Instant.now().plus(drainTimeout);
        boolean publishRequested = options.publish() || options.run();
        boolean drainRequested = options.drain() || options.run();

        if (publishRequested) {
            PublisherFailureMode mode = options.publisherCrash()
                    ? PublisherFailureMode.AFTER_SEND_BEFORE_SENT_UPDATE
                    : PublisherFailureMode.NONE;
            for (int attempt = 0; attempt < 10_000 && Instant.now().isBefore(deadline); attempt++) {
                try {
                    PublisherBatchResult batch = publisher.publishBatch(mode);
                    publication = publication.plus(batch);
                    mode = PublisherFailureMode.NONE;
                    if (batch.claimed() == 0) {
                        break;
                    }
                } catch (SimulatedPublisherCrashException exception) {
                    System.out.println("Injected publisher crash: " + exception.getMessage());
                    break;
                }
            }
        }

        if (drainRequested && !options.publisherCrash()) {
            ConsumerFailureMode mode = options.consumerBeforeCommit()
                    ? ConsumerFailureMode.BEFORE_DATABASE_COMMIT
                    : options.consumerAfterCommit()
                            ? ConsumerFailureMode.AFTER_COMMIT_BEFORE_DELETE
                            : ConsumerFailureMode.NONE;
            for (int attempt = 0; attempt < 10_000 && Instant.now().isBefore(deadline); attempt++) {
                try {
                    ConsumerBatchResult batch = mode == ConsumerFailureMode.NONE
                            ? pollWorkers()
                            : consumer.pollOnce(mode);
                    consumption = consumption.plus(batch);
                    pauseAfterAcceptedBatch(options.paceMillis(), batch.accepted());
                    if (mode != ConsumerFailureMode.NONE) {
                        break;
                    }
                    if (batch.received() == 0 && queue.depth().total() == 0) {
                        break;
                    }
                } catch (SimulatedConsumerCrashException exception) {
                    System.out.println("Injected consumer crash: " + exception.getMessage());
                    break;
                }
            }
        }

        printReport(fixture, imported, publication, consumption, Instant.now().isBefore(deadline));
    }

    private void runDeliveries(Options options) {
        Instant deadline = Instant.now().plus(drainTimeout);
        int published = 0;
        int completed = 0;
        boolean publish = options.deliveryPublish() || options.deliveryRun();
        boolean drain = options.deliveryDrain() || options.deliveryRun();
        for (int iteration = 0; iteration < 100 && Instant.now().isBefore(deadline); iteration++) {
            int sent = publish ? deliveryPublisher.publishBatch() : 0;
            int handled = drain ? deliveryConsumer.pollOnce() : 0;
            published += sent;
            completed += handled;
            if (sent == 0 && handled == 0) break;
        }
        System.out.printf("Delivery publisher: sent=%d; consumer: completed=%d; queue=%d; DLQ=%d%n",
                published, completed, deliveryQueue.depth().total(), deliveryDlq.depth().total());
    }

    private void runDeliveryDaemon() {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread worker = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            worker.interrupt();
        }, "delivery-shutdown"));
        Path heartbeat = Path.of("/tmp/courtpulse-delivery-worker.heartbeat");
        while (running.get()) {
            try {
            deliveryPublisher.publishBatch();
            deliveryConsumer.pollOnce();
            var sourceDepth = deliveryQueue.depth();
            var dlqDepth = deliveryDlq.depth();
            Instant observed = Instant.now();
            deliveryWork.observeDlqDepth(dlqDepth.total(), observed);
            operationalTelemetry.queue("alert_deliveries", sourceDepth.visible(),
                    sourceDepth.inFlight(), sourceDepth.delayed(), observed);
            operationalTelemetry.queue("alert_deliveries_dlq", dlqDepth.visible(),
                    dlqDepth.inFlight(), dlqDepth.delayed(), observed);
            operationalTelemetry.heartbeat("delivery", observed, true);
            } catch (RuntimeException exception) {
                operationalTelemetry.heartbeat("delivery", Instant.now(), false);
                throw exception;
            }
            try {
                Files.writeString(heartbeat, Instant.now().toString(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Thread.sleep(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException exception) {
                throw new IllegalStateException("Delivery worker heartbeat failed", exception);
            }
        }
    }

    private void runReconciliationDaemon() {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread worker = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            worker.interrupt();
        }, "reconciliation-shutdown"));
        Path heartbeat = Path.of("/tmp/courtpulse-reconciliation-worker.heartbeat");
        while (running.get()) {
            try {
            reconciliation.reconcileAvailable(10);
            var sourceDepth = queue.depth();
            var dlqDepth = dlq.depth();
            Instant observed = Instant.now();
            operationalTelemetry.queue("game_events", sourceDepth.visible(),
                    sourceDepth.inFlight(), sourceDepth.delayed(), observed);
            operationalTelemetry.queue("game_events_dlq", dlqDepth.visible(),
                    dlqDepth.inFlight(), dlqDepth.delayed(), observed);
            operationalTelemetry.heartbeat("reconciliation", observed, true);
            } catch (RuntimeException exception) {
                operationalTelemetry.heartbeat("reconciliation", Instant.now(), false);
                throw exception;
            }
            try {
                Files.writeString(heartbeat, Instant.now().toString(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Thread.sleep(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException exception) {
                throw new IllegalStateException("Reconciliation worker heartbeat failed", exception);
            }
        }
    }

    private static void pauseAfterAcceptedBatch(long paceMillis, long accepted) {
        if (paceMillis == 0 || accepted == 0) {
            return;
        }
        try {
            Thread.sleep(paceMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Paced replay interrupted", exception);
        }
    }

    private ConsumerBatchResult pollWorkers() {
        if (workerConcurrency == 1) {
            return consumer.pollOnce();
        }
        ExecutorService executor = Executors.newFixedThreadPool(workerConcurrency);
        try {
            List<Future<ConsumerBatchResult>> futures = executor.invokeAll(
                    java.util.Collections.nCopies(workerConcurrency, consumer::pollOnce));
            ConsumerBatchResult result = ConsumerBatchResult.empty();
            for (Future<ConsumerBatchResult> future : futures) {
                result = result.plus(future.get());
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Queue drain interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Queue worker failed", exception.getCause());
        } finally {
            executor.shutdown();
        }
    }

    private void printReport(
            LoadedFixture fixture,
            ImportResult imported,
            PublisherBatchResult publication,
            ConsumerBatchResult consumption,
            boolean beforeDeadline) {
        QueueDepth queueDepth = queue.depth();
        QueueDepth dlqDepth = dlq.depth();
        OutboxStatusCounts statuses = outbox.statusCounts();
        DatabaseCounts counts = inspection.counts();
        boolean hasCheckpoint = counts.gameCheckpoints() > 0;
        long version = hasCheckpoint ? inspection.checkpointVersion(fixture.game().gameId()) : 0;
        GameState state = hasCheckpoint ? processing.readCheckpoint(fixture.game().gameId()) : null;
        boolean drained = queueDepth.total() == 0;

        System.out.println("CourtPulse bounded queue replay");
        System.out.printf("Imported: raw=%d canonical=%d outbox=%d%n",
                imported.insertedRawPayloads(), imported.insertedCanonicalEvents(), imported.insertedOutboxRecords());
        System.out.printf("Publisher: claimed=%d sent=%d retried=%d failed=%d lostLease=%d%n",
                publication.claimed(), publication.sent(), publication.retryScheduled(),
                publication.failed(), publication.lostLease());
        System.out.printf("Consumer: received=%d accepted=%d suppressed=%d deleted=%d failed=%d%n",
                consumption.received(), consumption.accepted(), consumption.suppressed(),
                consumption.deleted(), consumption.failed());
        System.out.printf("Queue: visible=%d inFlight=%d delayed=%d; DLQ visible=%d%n",
                queueDepth.visible(), queueDepth.inFlight(), queueDepth.delayed(), dlqDepth.visible());
        System.out.printf("Outbox: pending=%d publishing=%d retry=%d sent=%d failed=%d deferred=%d%n",
                statuses.pending(), statuses.publishing(), statuses.retryScheduled(),
                statuses.sent(), statuses.failed(), statuses.deferred());
        System.out.printf("Database: processed=%d checkpointVersion=%d alerts=%d%n",
                counts.processedEvents(), version, counts.alertInstances());
        if (state != null) {
            System.out.printf("Final score: HOME %d - AWAY %d; player_ace=%d%n",
                    state.homeScore(), state.awayScore(), state.pointsFor("player_ace"));
            System.out.println("Final-state checksum: " + StateChecksum.sha256(state));
        } else {
            System.out.println("Final score/checksum: unavailable (fixture has not been imported)");
        }
        System.out.printf("Drain complete before deadline: %s (workers=%d)%n",
                drained && beforeDeadline, workerConcurrency);
    }

    static void printUsage() {
        System.out.println("Usage: queue-replay-cli [--reset-import] [--run | --publish | --drain] [--inspect]");
        System.out.println("       [--simulate-publisher-after-send]");
        System.out.println("       [--simulate-consumer-before-commit | --simulate-consumer-after-commit]");
        System.out.println("       [--pace-ms=0..10000] (use consumer batch size 1 for event-by-event pacing)");
        System.out.println("       [--delivery-publish | --delivery-drain | --delivery-run] (local Mailpit only)");
        System.out.println("       [--delivery-daemon] (bounded continuous worker; SIGTERM stops it)");
        System.out.println("       [--correction-fixture=/path/fixture.json] [--reconcile-game=game-id]");
        System.out.println("       [--reconciliation-run | --reconciliation-daemon]");
    }

    private record Options(
            boolean resetImport,
            boolean publish,
            boolean drain,
            boolean run,
            boolean inspect,
            boolean help,
            long paceMillis,
            boolean publisherCrash,
            boolean consumerBeforeCommit,
            boolean consumerAfterCommit,
            boolean deliveryPublish,
            boolean deliveryDrain,
            boolean deliveryRun,
            boolean deliveryDaemon,
            String correctionFixture,
            String reconcileGame,
            boolean reconciliationRun,
            boolean reconciliationDaemon) {
        static Options parse(ApplicationArguments arguments) {
            List<String> unknown = arguments.getOptionNames().stream()
                    .filter(option -> !SUPPORTED.contains(option) && !DELIVERY_OPTIONS.contains(option))
                    .filter(option -> !option.startsWith("spring."))
                    .filter(option -> !option.startsWith("logging."))
                    .filter(option -> !option.startsWith("courtpulse."))
                    .sorted()
                    .toList();
            if (!unknown.isEmpty() || !arguments.getNonOptionArgs().isEmpty()) {
                throw new IllegalArgumentException("Unsupported arguments; use --help");
            }
            Options options = new Options(
                    arguments.containsOption("reset-import"),
                    arguments.containsOption("publish"),
                    arguments.containsOption("drain"),
                    arguments.containsOption("run"),
                    arguments.containsOption("inspect"),
                    arguments.containsOption("help"),
                    parsePace(arguments),
                    arguments.containsOption("simulate-publisher-after-send"),
                    arguments.containsOption("simulate-consumer-before-commit"),
                    arguments.containsOption("simulate-consumer-after-commit"),
                    arguments.containsOption("delivery-publish"),
                    arguments.containsOption("delivery-drain"),
                    arguments.containsOption("delivery-run"),
                    arguments.containsOption("delivery-daemon"),
                    oneValue(arguments, "correction-fixture"),
                    oneValue(arguments, "reconcile-game"),
                    arguments.containsOption("reconciliation-run"),
                    arguments.containsOption("reconciliation-daemon"));
            if (options.consumerBeforeCommit && options.consumerAfterCommit) {
                throw new IllegalArgumentException("Choose only one consumer failure injection");
            }
            if (options.run && (options.publish || options.drain)) {
                throw new IllegalArgumentException("--run cannot be combined with --publish or --drain");
            }
            int deliveryModes = (options.deliveryPublish ? 1 : 0)
                    + (options.deliveryDrain ? 1 : 0) + (options.deliveryRun ? 1 : 0)
                    + (options.deliveryDaemon ? 1 : 0);
            if (deliveryModes > 1 || (deliveryModes != 0
                    && (options.resetImport || options.publish || options.drain || options.run || options.inspect))) {
                throw new IllegalArgumentException("Choose one delivery action without game replay actions");
            }
            if ((options.correctionFixture != null || options.reconcileGame != null
                    || options.reconciliationRun || options.reconciliationDaemon)
                    && (deliveryModes != 0 || options.resetImport || options.publish || options.drain
                            || options.run || options.inspect || options.publisherCrash
                            || options.consumerBeforeCommit || options.consumerAfterCommit)) {
                throw new IllegalArgumentException("Correction actions cannot be combined with replay actions");
            }
            boolean publishes = options.run || options.publish;
            boolean drains = options.run || options.drain;
            if (options.publisherCrash && !publishes) {
                throw new IllegalArgumentException("Publisher failure injection requires --run or --publish");
            }
            if ((options.consumerBeforeCommit || options.consumerAfterCommit) && !drains) {
                throw new IllegalArgumentException("Consumer failure injection requires --run or --drain");
            }
            if (!options.help && !options.resetImport && !publishes && !drains && !options.inspect
                    && deliveryModes == 0 && options.correctionFixture == null
                    && options.reconcileGame == null && !options.reconciliationRun
                    && !options.reconciliationDaemon) {
                throw new IllegalArgumentException("Choose an action; use --help");
            }
            return options;
        }

        private static String oneValue(ApplicationArguments arguments, String name) {
            if (!arguments.containsOption(name)) return null;
            List<String> values = arguments.getOptionValues(name);
            if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
                throw new IllegalArgumentException("--" + name + " requires exactly one value");
            }
            return values.getFirst();
        }

        private static long parsePace(ApplicationArguments arguments) {
            if (!arguments.containsOption("pace-ms")) {
                return 0;
            }
            List<String> values = arguments.getOptionValues("pace-ms");
            if (values == null || values.size() != 1) {
                throw new IllegalArgumentException("--pace-ms requires exactly one value");
            }
            try {
                long value = Long.parseLong(values.getFirst());
                if (value < 0 || value > 10_000) {
                    throw new IllegalArgumentException("--pace-ms must be between 0 and 10000");
                }
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("--pace-ms must be an integer", exception);
            }
        }
    }
}
