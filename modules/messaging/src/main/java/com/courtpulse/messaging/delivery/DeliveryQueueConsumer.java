package com.courtpulse.messaging.delivery;

import com.courtpulse.messaging.queue.QueuePort;
import com.courtpulse.messaging.queue.ReceivedQueueMessage;
import com.courtpulse.persistence.ClaimedEmailDelivery;
import com.courtpulse.persistence.JdbcDeliveryWorkRepository;
import com.courtpulse.observability.TraceContext;
import io.opentelemetry.api.trace.SpanKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/** A poison message never blocks another delivery: each FIFO group is the delivery ID. */
public final class DeliveryQueueConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryQueueConsumer.class);
    private final JdbcDeliveryWorkRepository work;
    private final QueuePort queue;
    private final EmailSender email;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String owner;
    private final String publicBaseUrl;

    public DeliveryQueueConsumer(
            JdbcDeliveryWorkRepository work, QueuePort queue, EmailSender email,
            TransactionTemplate transactions, Clock clock, String owner) {
        this(work, queue, email, transactions, clock, owner, null);
    }

    /** {@code publicBaseUrl} (for example the CloudFront URL) adds a link to the game, if set. */
    public DeliveryQueueConsumer(
            JdbcDeliveryWorkRepository work, QueuePort queue, EmailSender email,
            TransactionTemplate transactions, Clock clock, String owner, String publicBaseUrl) {
        this.work = work;
        this.queue = queue;
        this.email = email;
        this.transactions = transactions;
        this.clock = clock;
        this.owner = owner;
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()
                && !publicBaseUrl.matches("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?|http://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?")) {
            throw new IllegalArgumentException("Public base URL must be an HTTPS origin without a path");
        }
        this.publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank() ? null : publicBaseUrl;
    }

    String body(ClaimedEmailDelivery delivery) {
        StringBuilder body = new StringBuilder(delivery.title())
                .append("\nGame: ").append(delivery.gameLabel());
        if (publicBaseUrl != null) {
            body.append("\nOpen the game: ").append(publicBaseUrl).append("/games/")
                    .append(java.net.URLEncoder.encode(delivery.gameId(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return body.append("\n\nYou receive this because email alerts are enabled in your CourtPulse")
                .append(" notification settings.").toString();
    }

    public int pollOnce() {
        transactions.executeWithoutResult(status -> work.recoverExpired(clock.instant()));
        int completed = 0;
        for (ReceivedQueueMessage message : queue.receive(10, Duration.ofSeconds(2))) {
            try (var span = TraceContext.continueFrom(message.traceparent(),
                    "alert-delivery consume", SpanKind.CONSUMER)) {
            span.span().setAttribute("messaging.message.delivery_count", message.receiveCount());
            LOGGER.debug("Delivery queue message received messageId={} receiveCount={}",
                    message.providerMessageId(), message.receiveCount());
            UUID deliveryId;
            try {
                deliveryId = UUID.fromString(message.body());
            } catch (IllegalArgumentException exception) {
                // Leave malformed messages for SQS redrive to the dedicated DLQ.
                LOGGER.debug("Malformed delivery identity left for queue redrive messageId={}",
                        message.providerMessageId());
                continue;
            }
            ClaimedEmailDelivery delivery = transactions.execute(status ->
                    work.claimDelivery(deliveryId, owner, clock.instant(), Duration.ofSeconds(30)));
            if (delivery == null) {
                String state = work.deliveryStatus(deliveryId);
                if (!"LEASED".equals(state)) {
                    queue.delete(message.receiptHandle());
                }
                continue;
            }
            if (!delivery.optedIn()) {
                finish(delivery, "CANCELLED", "preference_disabled", null, null);
            } else {
                try {
                    String providerId = email.send(delivery.address(),
                            "CourtPulse: " + delivery.title(), body(delivery));
                    finish(delivery, "SENT", null, providerId, null);
                } catch (EmailSendException exception) {
                    boolean retry = exception.retryable() && delivery.attempt() < 5;
                    long baseSeconds = Math.min(300, 10L << (delivery.attempt() - 1));
                    Instant next = retry ? clock.instant().plusSeconds(baseSeconds)
                            .plusMillis(ThreadLocalRandom.current().nextLong(baseSeconds * 250 + 1)) : null;
                    finish(delivery, retry ? "TRANSIENT_FAILURE" : "PERMANENT_FAILURE",
                            exception.code(), null, next);
                }
            }
            queue.delete(message.receiptHandle());
            completed++;
            }
        }
        return completed;
    }

    private void finish(
            ClaimedEmailDelivery delivery, String outcome, String errorCode,
            String providerId, Instant nextAttempt) {
        boolean updated = Boolean.TRUE.equals(transactions.execute(status -> work.finishDelivery(
                delivery, owner, outcome, errorCode, providerId, clock.instant(), nextAttempt)));
        if (!updated) throw new IllegalStateException("Email delivery lease was lost");
    }
}
