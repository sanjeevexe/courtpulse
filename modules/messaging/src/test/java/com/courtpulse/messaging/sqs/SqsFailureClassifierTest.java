package com.courtpulse.messaging.sqs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsFailureClassifierTest {
    private final SqsFailureClassifier classifier = new SqsFailureClassifier();

    @Test
    void retriesThrottlingAndServerFailures() {
        assertTrue(classifier.isRetryable(failure(429, "RequestThrottled")));
        assertTrue(classifier.isRetryable(failure(503, "ServiceUnavailable")));
    }

    @Test
    void rejectsInvalidRequestsAsPermanent() {
        assertFalse(classifier.isRetryable(failure(400, "InvalidParameterValue")));
        assertFalse(classifier.isRetryable(failure(404, "AWS.SimpleQueueService.NonExistentQueue")));
    }

    private static SqsException failure(int statusCode, String errorCode) {
        SqsException.Builder builder = SqsException.builder();
        builder.statusCode(statusCode);
        builder.message("safe test failure");
        builder.awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode(errorCode)
                        .errorMessage("safe test failure")
                        .serviceName("Sqs")
                        .build());
        return (SqsException) builder.build();
    }
}
