package com.courtpulse.messaging.consumer;

import com.courtpulse.messaging.queue.GameEventEnvelope;
import com.courtpulse.messaging.queue.GameEventEnvelopeCodec;
import com.courtpulse.messaging.queue.QueuePort;
import com.courtpulse.messaging.queue.ReceivedQueueMessage;
import com.courtpulse.persistence.DurableProcessingResult;
import com.courtpulse.persistence.DurableGameProcessor;
import com.courtpulse.persistence.FailureMode;
import com.courtpulse.persistence.JdbcOperationalTelemetryRepository;
import com.courtpulse.observability.TraceContext;
import io.opentelemetry.api.trace.SpanKind;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameEventQueueConsumer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameEventQueueConsumer.class);

    private final QueuePort queue;
    private final GameEventEnvelopeCodec codec;
    private final CanonicalEventEnvelopeValidator validator;
    private final DurableGameProcessor processor;
    private final int batchSize;
    private final Duration waitTime;
    private final JdbcOperationalTelemetryRepository operationalTelemetry;
    private final AtomicBoolean closed = new AtomicBoolean();

    public GameEventQueueConsumer(
            QueuePort queue,
            GameEventEnvelopeCodec codec,
            CanonicalEventEnvelopeValidator validator,
            DurableGameProcessor processor,
            int batchSize,
            Duration waitTime) {
        this(queue, codec, validator, processor, batchSize, waitTime, null);
    }

    public GameEventQueueConsumer(
            QueuePort queue,
            GameEventEnvelopeCodec codec,
            CanonicalEventEnvelopeValidator validator,
            DurableGameProcessor processor,
            int batchSize,
            Duration waitTime,
            JdbcOperationalTelemetryRepository operationalTelemetry) {
        this.queue = queue;
        this.codec = codec;
        this.validator = validator;
        this.processor = processor;
        this.batchSize = batchSize;
        this.waitTime = waitTime;
        this.operationalTelemetry = operationalTelemetry;
    }

    public ConsumerBatchResult pollOnce() {
        return pollOnce(ConsumerFailureMode.NONE);
    }

    public ConsumerBatchResult pollOnce(ConsumerFailureMode failureMode) {
        if (closed.get()) {
            return ConsumerBatchResult.empty();
        }
        List<ReceivedQueueMessage> messages = queue.receive(batchSize, waitTime);
        ConsumerBatchResult result = ConsumerBatchResult.empty();
        for (ReceivedQueueMessage message : messages) {
            if (closed.get()) {
                break;
            }
            result = result.plus(handle(message, failureMode));
            failureMode = ConsumerFailureMode.NONE;
        }
        return result;
    }

    private ConsumerBatchResult handle(
            ReceivedQueueMessage message, ConsumerFailureMode failureMode) {
        try {
            GameEventEnvelope envelope = codec.decode(message.body());
            try (var span = TraceContext.continueFrom(
                    envelope.traceparent() != null ? envelope.traceparent() : message.traceparent(),
                    "game-event consume", SpanKind.CONSUMER)) {
            span.span().setAttribute("courtpulse.event.id", envelope.eventId());
            validator.validate(envelope);
            FailureMode processingFailure = failureMode == ConsumerFailureMode.BEFORE_DATABASE_COMMIT
                    ? FailureMode.BEFORE_COMMIT
                    : FailureMode.NONE;
            DurableProcessingResult processing = processor.processEvent(
                    envelope.eventId(), processingFailure);
            if (!processing.accepted() && operationalTelemetry != null) {
                operationalTelemetry.duplicateSuppressed();
            }
            if (failureMode == ConsumerFailureMode.AFTER_COMMIT_BEFORE_DELETE) {
                throw new SimulatedConsumerCrashException(
                        "Simulated consumer crash after commit for event " + envelope.eventId());
            }
            queue.delete(message.receiptHandle());
            LOGGER.atInfo()
                    .addKeyValue("gameId", envelope.gameId())
                    .addKeyValue("eventId", envelope.eventId())
                    .addKeyValue("receiveCount", message.receiveCount())
                    .addKeyValue("duplicate", !processing.accepted())
                    .log("Processed queued canonical event");
            return new ConsumerBatchResult(
                    1,
                    processing.accepted() ? 1 : 0,
                    processing.accepted() ? 0 : 1,
                    1,
                    0);
            }
        } catch (RuntimeException exception) {
            LOGGER.atWarn()
                    .addKeyValue("providerMessageId", message.providerMessageId())
                    .addKeyValue("receiveCount", message.receiveCount())
                    .addKeyValue("errorType", exception.getClass().getSimpleName())
                    .log("Queue message processing failed");
            if (exception instanceof SimulatedConsumerCrashException) {
                throw exception;
            }
            return new ConsumerBatchResult(1, 0, 0, 0, 1);
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
