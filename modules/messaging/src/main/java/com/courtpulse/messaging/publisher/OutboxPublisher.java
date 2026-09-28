package com.courtpulse.messaging.publisher;

import com.courtpulse.messaging.queue.GameEventEnvelope;
import com.courtpulse.messaging.queue.GameEventEnvelopeCodec;
import com.courtpulse.messaging.queue.QueuePort;
import com.courtpulse.messaging.queue.QueuePublishException;
import com.courtpulse.messaging.queue.QueueSendRequest;
import com.courtpulse.messaging.queue.SendOutcome;
import com.courtpulse.persistence.JdbcOutboxPublicationRepository;
import com.courtpulse.persistence.LeasedOutboxRecord;
import com.courtpulse.observability.TraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

public final class OutboxPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int SQS_BATCH_LIMIT = 10;

    private final JdbcOutboxPublicationRepository repository;
    private final QueuePort queue;
    private final GameEventEnvelopeCodec codec;
    private final TransactionTemplate transactions;
    private final PublicationRetryPolicy retryPolicy;
    private final Clock clock;
    private final String leaseOwner;
    private final Duration leaseDuration;
    private final int batchSize;

    public OutboxPublisher(
            JdbcOutboxPublicationRepository repository,
            QueuePort queue,
            GameEventEnvelopeCodec codec,
            TransactionTemplate transactions,
            PublicationRetryPolicy retryPolicy,
            Clock clock,
            String leaseOwner,
            Duration leaseDuration,
            int batchSize) {
        this.repository = repository;
        this.queue = queue;
        this.codec = codec;
        this.transactions = transactions;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.leaseOwner = leaseOwner;
        this.leaseDuration = leaseDuration;
        this.batchSize = batchSize;
    }

    public PublisherBatchResult publishBatch() {
        return publishBatch(PublisherFailureMode.NONE);
    }

    public PublisherBatchResult publishBatch(PublisherFailureMode failureMode) {
        Instant claimTime = clock.instant();
        List<LeasedOutboxRecord> claimed = transactions.execute(status ->
                repository.claimGameEvents(leaseOwner, batchSize, claimTime, leaseDuration));
        if (claimed == null || claimed.isEmpty()) {
            return PublisherBatchResult.empty();
        }

        if (failureMode == PublisherFailureMode.NONE && claimed.size() > 1) {
            return publishClaimed(claimed);
        }
        PublisherBatchResult result = PublisherBatchResult.empty();
        for (LeasedOutboxRecord record : claimed) {
            result = result.plus(publishOne(record, failureMode));
            failureMode = PublisherFailureMode.NONE;
        }
        return result;
    }

    private PublisherBatchResult publishClaimed(List<LeasedOutboxRecord> chunk) {
        List<Span> spans = new ArrayList<>(chunk.size());
        List<QueueSendRequest> requests = new ArrayList<>(chunk.size());
        for (LeasedOutboxRecord record : chunk) {
            Span span = TraceContext.startDetached(record.traceparent(), "game-event publish", SpanKind.PRODUCER);
            span.setAttribute("courtpulse.event.id", record.eventId());
            String traceparent = TraceContext.format(span.getSpanContext());
            spans.add(span);
            requests.add(new QueueSendRequest(codec.encode(envelope(record, traceparent)),
                    record.messageGroupId(), record.deduplicationKey(), traceparent));
        }
        List<SendOutcome> outcomes = sendInBatches(requests);
        PublisherBatchResult result = PublisherBatchResult.empty();
        List<LeasedOutboxRecord> sent = new ArrayList<>();
        for (int index = 0; index < chunk.size(); index++) {
            SendOutcome outcome = outcomes.get(index);
            if (outcome.succeeded()) {
                sent.add(chunk.get(index));
            } else {
                spans.get(index).recordException(outcome.failure());
                result = result.plus(failed(chunk.get(index), outcome.failure()));
            }
        }
        Instant sentAt = clock.instant();
        List<Boolean> owned = transactions.execute(status -> sent.stream()
                .map(record -> repository.markSent(record.outboxId(), leaseOwner, sentAt)).toList());
        for (int index = 0; index < sent.size(); index++) {
            boolean kept = owned != null && Boolean.TRUE.equals(owned.get(index));
            result = result.plus(new PublisherBatchResult(1, kept ? 1 : 0, 0, 0, kept ? 0 : 1));
        }
        spans.forEach(Span::end);
        LOGGER.atDebug().addKeyValue("sent", sent.size()).addKeyValue("failed", chunk.size() - sent.size())
                .log("Published canonical event batch");
        return result;
    }

    /**
     * A claim holds at most one row per game (older unsent rows block newer ones), so no two
     * requests share a FIFO group: SQS batches of ten can go out concurrently without reordering.
     */
    private List<SendOutcome> sendInBatches(List<QueueSendRequest> requests) {
        if (requests.size() <= SQS_BATCH_LIMIT) {
            return queue.sendBatch(requests);
        }
        List<Future<List<SendOutcome>>> batches = new ArrayList<>();
        try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int start = 0; start < requests.size(); start += SQS_BATCH_LIMIT) {
                List<QueueSendRequest> batch =
                        requests.subList(start, Math.min(requests.size(), start + SQS_BATCH_LIMIT));
                batches.add(senders.submit(() -> queue.sendBatch(batch)));
            }
            List<SendOutcome> outcomes = new ArrayList<>(requests.size());
            for (Future<List<SendOutcome>> batch : batches) {
                outcomes.addAll(batch.get());
            }
            return outcomes;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing", exception);
        } catch (ExecutionException exception) {
            // Unsent or unmarked rows keep their lease and are reclaimed after it expires.
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Batch publish failed", exception.getCause());
        }
    }

    private GameEventEnvelope envelope(LeasedOutboxRecord record, String traceparent) {
        return new GameEventEnvelope(
                record.outboxId().toString(),
                GameEventEnvelope.MESSAGE_TYPE,
                GameEventEnvelope.CURRENT_SCHEMA_VERSION,
                record.eventId(),
                record.gameId(),
                record.sequence(),
                record.source(),
                record.providerEventId(),
                record.revision(),
                record.occurredAt(),
                record.outboxId(),
                record.deduplicationKey(),
                null,
                traceparent);
    }

    private PublisherBatchResult failed(LeasedOutboxRecord record, QueuePublishException exception) {
        RetryDecision decision = retryPolicy.afterFailure(record.attempt(), exception.retryable());
        Instant failureTime = clock.instant();
        if (decision.retry()) {
            boolean owned = Boolean.TRUE.equals(transactions.execute(status -> repository.scheduleRetry(
                    record.outboxId(), leaseOwner, failureTime, failureTime.plus(decision.delay()),
                    exception.getMessage())));
            return new PublisherBatchResult(1, 0, owned ? 1 : 0, 0, owned ? 0 : 1);
        }
        boolean owned = Boolean.TRUE.equals(transactions.execute(status -> repository.markFailed(
                record.outboxId(), leaseOwner, failureTime, exception.getMessage())));
        return new PublisherBatchResult(1, 0, 0, owned ? 1 : 0, owned ? 0 : 1);
    }

    private PublisherBatchResult publishOne(
            LeasedOutboxRecord record, PublisherFailureMode failureMode) {
        try (var span = TraceContext.continueFrom(record.traceparent(),
                "game-event publish", SpanKind.PRODUCER)) {
        span.span().setAttribute("courtpulse.event.id", record.eventId());
        GameEventEnvelope envelope = envelope(record, TraceContext.currentTraceparent());
        try {
            String providerMessageId = queue.send(new QueueSendRequest(
                    codec.encode(envelope),
                    record.messageGroupId(),
                    record.deduplicationKey(),
                    TraceContext.currentTraceparent()));
            LOGGER.atInfo()
                    .addKeyValue("outboxId", record.outboxId())
                    .addKeyValue("gameId", record.gameId())
                    .addKeyValue("eventId", record.eventId())
                    .addKeyValue("providerMessageId", providerMessageId)
                    .log("Published canonical event");
            if (failureMode == PublisherFailureMode.AFTER_SEND_BEFORE_SENT_UPDATE) {
                throw new SimulatedPublisherCrashException(
                        "Simulated publisher crash after send for outbox " + record.outboxId());
            }
            boolean owned = Boolean.TRUE.equals(transactions.execute(status ->
                    repository.markSent(record.outboxId(), leaseOwner, clock.instant())));
            return new PublisherBatchResult(1, owned ? 1 : 0, 0, 0, owned ? 0 : 1);
        } catch (SimulatedPublisherCrashException exception) {
            throw exception;
        } catch (QueuePublishException exception) {
            span.span().recordException(exception);
            return failed(record, exception);
        }
        }
    }
}
