package com.courtpulse.messaging.sqs;

import com.courtpulse.messaging.queue.QueueDepth;
import com.courtpulse.messaging.queue.QueuePort;
import com.courtpulse.messaging.queue.QueuePublishException;
import com.courtpulse.messaging.queue.QueueSendRequest;
import com.courtpulse.messaging.queue.SendOutcome;
import com.courtpulse.messaging.queue.ReceivedQueueMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

public final class SqsQueueAdapter implements QueuePort {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqsQueueAdapter.class);
    /**
     * SQS calls normally take milliseconds. Bounding each attempt turns a silently dropped
     * connection into a retryable timeout instead of a stall for the 30 s socket timeout.
     * Receives get their long-poll wait on top of the same margin.
     */
    static final Duration SEND_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);
    private static final AwsRequestOverrideConfiguration SEND_OVERRIDE = attemptTimeout(SEND_ATTEMPT_TIMEOUT);
    private final SqsClient client;
    private final String queueUrl;
    private final boolean closeClient;
    private final SqsFailureClassifier failureClassifier;

    public SqsQueueAdapter(SqsClient client, String queueUrl) {
        this(client, queueUrl, false, new SqsFailureClassifier());
    }

    public SqsQueueAdapter(SqsClient client, String queueUrl, boolean closeClient) {
        this(client, queueUrl, closeClient, new SqsFailureClassifier());
    }

    SqsQueueAdapter(
            SqsClient client,
            String queueUrl,
            boolean closeClient,
            SqsFailureClassifier failureClassifier) {
        this.client = client;
        this.queueUrl = queueUrl;
        this.closeClient = closeClient;
        this.failureClassifier = failureClassifier;
    }

    /** One SendMessageBatch call for up to ten messages; per-entry failures stay per entry. */
    @Override
    public List<SendOutcome> sendBatch(List<QueueSendRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        if (requests.size() > 10) {
            throw new IllegalArgumentException("SQS accepts at most ten messages per batch");
        }
        List<SendMessageBatchRequestEntry> entries = new java.util.ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            QueueSendRequest request = requests.get(index);
            var entry = SendMessageBatchRequestEntry.builder()
                    .id(Integer.toString(index))
                    .messageBody(request.body())
                    .messageGroupId(request.messageGroupId())
                    .messageDeduplicationId(request.deduplicationId());
            if (request.traceparent() != null) {
                entry.messageAttributes(Map.of("traceparent", MessageAttributeValue.builder()
                        .dataType("String").stringValue(request.traceparent()).build()));
            }
            entries.add(entry.build());
        }
        SendOutcome[] outcomes = new SendOutcome[requests.size()];
        try {
            var response = client.sendMessageBatch(
                    SendMessageBatchRequest.builder().queueUrl(queueUrl).entries(entries)
                            .overrideConfiguration(SEND_OVERRIDE).build());
            response.successful().forEach(success ->
                    outcomes[Integer.parseInt(success.id())] = SendOutcome.sent(success.messageId()));
            response.failed().forEach(failure -> outcomes[Integer.parseInt(failure.id())] =
                    SendOutcome.failed(new QueuePublishException(
                            "SQS batch entry failed: " + failure.code(),
                            !Boolean.TRUE.equals(failure.senderFault()), null)));
        } catch (SdkException exception) {
            QueuePublishException failure = new QueuePublishException(
                    "SQS batch send failed: " + safeMessage(exception),
                    failureClassifier.isRetryable(exception), exception);
            java.util.Arrays.fill(outcomes, SendOutcome.failed(failure));
        }
        for (int index = 0; index < outcomes.length; index++) {
            if (outcomes[index] == null) {
                outcomes[index] = SendOutcome.failed(new QueuePublishException(
                        "SQS batch response omitted an entry", true, null));
            }
        }
        return List.of(outcomes);
    }

    @Override
    public String send(QueueSendRequest request) {
        try {
            var builder = SendMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .messageBody(request.body())
                            .messageGroupId(request.messageGroupId())
                            .messageDeduplicationId(request.deduplicationId())
                            .overrideConfiguration(SEND_OVERRIDE);
            if (request.traceparent() != null) {
                builder.messageAttributes(Map.of("traceparent", MessageAttributeValue.builder()
                        .dataType("String").stringValue(request.traceparent()).build()));
            }
            return client.sendMessage(builder.build())
                    .messageId();
        } catch (SdkException exception) {
            throw new QueuePublishException(
                    "SQS send failed: " + safeMessage(exception),
                    failureClassifier.isRetryable(exception),
                    exception);
        }
    }

    @Override
    public List<ReceivedQueueMessage> receive(int maxMessages, Duration waitTime) {
        int waitSeconds = Math.toIntExact(Math.min(20, Math.max(0, waitTime.toSeconds())));
        List<ReceivedQueueMessage> received = client.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(Math.min(10, Math.max(1, maxMessages)))
                        .waitTimeSeconds(waitSeconds)
                        .messageSystemAttributeNamesWithStrings("ApproximateReceiveCount")
                        .messageAttributeNames("traceparent")
                        .overrideConfiguration(attemptTimeout(
                                Duration.ofSeconds(waitSeconds).plus(SEND_ATTEMPT_TIMEOUT)))
                        .build())
                .messages().stream()
                .map(message -> new ReceivedQueueMessage(
                        message.messageId(),
                        message.receiptHandle(),
                        message.body(),
                        Integer.parseInt(message.attributesAsStrings()
                                .getOrDefault("ApproximateReceiveCount", "1")),
                        message.messageAttributes().containsKey("traceparent")
                                ? message.messageAttributes().get("traceparent").stringValue() : null))
                .toList();
        LOGGER.debug("SQS queue polled queueUrl={} received={}", queueUrl, received.size());
        return received;
    }

    @Override
    public void delete(String receiptHandle) {
        client.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .overrideConfiguration(SEND_OVERRIDE)
                .build());
    }

    @Override
    public QueueDepth depth() {
        Map<QueueAttributeName, String> attributes = client.getQueueAttributes(
                        GetQueueAttributesRequest.builder()
                                .queueUrl(queueUrl)
                                .attributeNames(
                                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED)
                                .overrideConfiguration(SEND_OVERRIDE)
                                .build())
                .attributes();
        return new QueueDepth(
                value(attributes, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES),
                value(attributes, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE),
                value(attributes, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED));
    }

    @Override
    public void close() {
        if (closeClient) {
            client.close();
        }
    }

    private static long value(Map<QueueAttributeName, String> attributes, QueueAttributeName name) {
        return Long.parseLong(attributes.getOrDefault(name, "0"));
    }

    private static String safeMessage(SdkException exception) {
        if (exception instanceof SqsException serviceFailure) {
            String code = serviceFailure.awsErrorDetails() == null
                    ? "unknown"
                    : serviceFailure.awsErrorDetails().errorCode();
            return "status=" + serviceFailure.statusCode() + " code=" + code;
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String safe = message
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("(?i)(secret|token|password|credential)=[^ ]+", "$1=[redacted]");
        return exception.getClass().getSimpleName() + ": "
                + safe.substring(0, Math.min(safe.length(), 500));
    }

    private static AwsRequestOverrideConfiguration attemptTimeout(Duration timeout) {
        return AwsRequestOverrideConfiguration.builder().apiCallAttemptTimeout(timeout).build();
    }
}
