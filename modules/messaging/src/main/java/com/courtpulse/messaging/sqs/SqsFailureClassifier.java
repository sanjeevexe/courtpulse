package com.courtpulse.messaging.sqs;

import java.util.Set;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.model.SqsException;

/** Classifies only network/SDK-retryable, throttling, and server failures as transient. */
public final class SqsFailureClassifier {
    private static final Set<String> TRANSIENT_CODES = Set.of(
            "RequestThrottled", "Throttling", "ThrottlingException", "ServiceUnavailable");

    public boolean isRetryable(SdkException exception) {
        if (exception instanceof SqsException serviceFailure) {
            String errorCode = serviceFailure.awsErrorDetails() == null
                    ? null
                    : serviceFailure.awsErrorDetails().errorCode();
            return serviceFailure.isThrottlingException()
                    || serviceFailure.statusCode() == 429
                    || serviceFailure.statusCode() >= 500
                    || (errorCode != null && TRANSIENT_CODES.contains(errorCode));
        }
        return exception.retryable();
    }
}
