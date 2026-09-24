package com.courtpulse.messaging.sqs;

import com.courtpulse.messaging.queue.QueueDepth;
import com.courtpulse.messaging.queue.QueuePort;
import com.courtpulse.messaging.queue.QueuePublishException;
import com.courtpulse.messaging.queue.QueueSendRequest;
import com.courtpulse.messaging.queue.ReceivedQueueMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

public final class SqsQueueAdapter implements QueuePort {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqsQueueAdapter.class);
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

    @Override
    public String send(QueueSendRequest request) {
        try {
            return client.sendMessage(SendMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .messageBody(request.body())
                            .messageGroupId(request.messageGroupId())
                            .messageDeduplicationId(request.deduplicationId())
                            .build())
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
                        .build())
                .messages().stream()
                .map(message -> new ReceivedQueueMessage(
                        message.messageId(),
                        message.receiptHandle(),
                        message.body(),
                        Integer.parseInt(message.attributesAsStrings()
                                .getOrDefault("ApproximateReceiveCount", "1"))))
                .toList();
        LOGGER.debug("SQS queue polled queueUrl={} received={}", queueUrl, received.size());
        return received;
    }

    @Override
    public void delete(String receiptHandle) {
        client.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
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
}
