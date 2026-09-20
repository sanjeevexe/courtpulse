package com.courtpulse.messaging.consumer;

public enum ConsumerFailureMode {
    NONE,
    BEFORE_DATABASE_COMMIT,
    AFTER_COMMIT_BEFORE_DELETE
}
