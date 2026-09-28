package com.courtpulse.messaging.delivery;

import com.courtpulse.messaging.queue.QueuePort;
import com.courtpulse.messaging.queue.QueuePublishException;
import com.courtpulse.messaging.queue.QueueSendRequest;
import com.courtpulse.persistence.DeliveryOutboxLease;
import com.courtpulse.persistence.JdbcDeliveryWorkRepository;
import com.courtpulse.observability.TraceContext;
import io.opentelemetry.api.trace.SpanKind;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

/** Safe at-least-once publication of delivery IDs; no private payload enters SQS. */
public final class DeliveryQueuePublisher {
    private final JdbcDeliveryWorkRepository work;
    private final QueuePort queue;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String owner;

    public DeliveryQueuePublisher(
            JdbcDeliveryWorkRepository work, QueuePort queue,
            TransactionTemplate transactions, Clock clock, String owner) {
        this.work = work;
        this.queue = queue;
        this.transactions = transactions;
        this.clock = clock;
        this.owner = owner;
    }

    public int publishBatch() {
        List<DeliveryOutboxLease> leased = transactions.execute(status -> {
            work.recoverExpired(clock.instant());
            return work.claimOutbox(owner, 10, clock.instant(), Duration.ofSeconds(20));
        });
        int published = 0;
        for (DeliveryOutboxLease item : leased) {
            try (var span = TraceContext.continueFrom(item.traceparent(),
                    "alert-delivery publish", SpanKind.PRODUCER)) {
            try {
                String providerId = queue.send(new QueueSendRequest(
                        item.deliveryId().toString(),
                        item.deliveryId().toString(),
                        item.outboxId() + ":" + item.attempt(),
                        TraceContext.currentTraceparent()));
                boolean marked = Boolean.TRUE.equals(transactions.execute(status ->
                        work.markPublished(item.outboxId(), owner, providerId, clock.instant())));
                if (marked) published++;
            } catch (QueuePublishException exception) {
                span.span().recordException(exception);
                boolean retry = exception.retryable() && item.attempt() < 5;
                transactions.executeWithoutResult(status -> work.publicationFailed(
                        item.outboxId(), owner,
                        retry ? "queue_transient_failure" : "queue_terminal_failure",
                        clock.instant(), item.attempt(), retry));
            }
            }
        }
        return published;
    }
}
