package com.courtpulse.messaging.sqs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
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

    @Test
    void networkFailuresAndTimeoutsAreTransient() {
        assertTrue(classifier.isRetryable(SdkClientException.builder().message("unreachable")
                .cause(new UnknownHostException("localstack")).build()));
        assertTrue(classifier.isRetryable(SdkClientException.builder().message("refused")
                .cause(new ConnectException("Connection refused")).build()));
        assertTrue(classifier.isRetryable(ApiCallAttemptTimeoutException.create(5_000)));
        assertTrue(classifier.isRetryable(AbortedException.create("Thread was interrupted")),
                "an interrupted send has an unknown outcome and must be retried, not failed");
    }

    @Test
    void clientConfigurationFailuresArePermanent() {
        assertFalse(classifier.isRetryable(SdkClientException.create("Unable to load credentials")));
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
