package com.courtpulse.messaging.sqs;

import java.io.IOException;
import java.util.Set;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.model.SqsException;

/**
 * Classifies network failures, timeouts, throttling, and server failures as transient. Client-side
 * configuration problems (credentials, endpoints) and 4xx request errors are permanent.
 */
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
        // An aborted call (the worker was interrupted, e.g. during shutdown) says nothing about the
        // message; its outcome is unknown, so it must be retried rather than failed.
        if (exception instanceof ApiCallTimeoutException || exception instanceof ApiCallAttemptTimeoutException
                || exception instanceof AbortedException) {
            return true;
        }
        // The SDK reports an unreachable, refusing, or dropped endpoint as a client exception
        // caused by an IOException; the service may be fine a moment later.
        if (exception instanceof SdkClientException && causedByIo(exception)) {
            return true;
        }
        return exception.retryable();
    }

    private static boolean causedByIo(Throwable exception) {
        for (Throwable cause = exception.getCause(); cause != null && cause != cause.getCause();
                cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
