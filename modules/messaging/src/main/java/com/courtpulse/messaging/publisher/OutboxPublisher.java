package com.courtpulse.messaging.publisher;

import com.courtpulse.messaging.queue.GameEventEnvelope;
import com.courtpulse.messaging.queue.GameEventEnvelopeCodec;
import com.courtpulse.messaging.queue.QueuePort;
import com.courtpulse.messaging.queue.QueuePublishException;
import com.courtpulse.messaging.queue.QueueSendRequest;
import com.courtpulse.persistence.JdbcOutboxPublicationRepository;
import com.courtpulse.persistence.LeasedOutboxRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

public final class OutboxPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

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

        PublisherBatchResult result = PublisherBatchResult.empty();
        for (LeasedOutboxRecord record : claimed) {
            result = result.plus(publishOne(record, failureMode));
            failureMode = PublisherFailureMode.NONE;
        }
        return result;
    }

    private PublisherBatchResult publishOne(
            LeasedOutboxRecord record, PublisherFailureMode failureMode) {
        GameEventEnvelope envelope = new GameEventEnvelope(
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
                null);
        try {
            String providerMessageId = queue.send(new QueueSendRequest(
                    codec.encode(envelope),
                    record.messageGroupId(),
                    record.deduplicationKey()));
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
            RetryDecision decision = retryPolicy.afterFailure(record.attempt(), exception.retryable());
            boolean owned;
            Instant failureTime = clock.instant();
            if (decision.retry()) {
                owned = Boolean.TRUE.equals(transactions.execute(status -> repository.scheduleRetry(
                        record.outboxId(),
                        leaseOwner,
                        failureTime,
                        failureTime.plus(decision.delay()),
                        exception.getMessage())));
                return new PublisherBatchResult(1, 0, owned ? 1 : 0, 0, owned ? 0 : 1);
            }
            owned = Boolean.TRUE.equals(transactions.execute(status -> repository.markFailed(
                    record.outboxId(), leaseOwner, failureTime, exception.getMessage())));
            return new PublisherBatchResult(1, 0, 0, owned ? 1 : 0, owned ? 0 : 1);
        }
    }
}
