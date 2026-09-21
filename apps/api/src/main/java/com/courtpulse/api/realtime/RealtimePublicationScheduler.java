package com.courtpulse.api.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class RealtimePublicationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimePublicationScheduler.class);
    private final RealtimeOutboxPublisher publisher;

    public RealtimePublicationScheduler(RealtimeOutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${courtpulse.realtime.publish-delay}")
    public void publish() {
        try {
            RealtimePublicationResult result = publisher.publishBatch();
            if (result.claimed() > 0) {
                LOGGER.atDebug()
                        .addKeyValue("claimed", result.claimed())
                        .addKeyValue("completed", result.completed())
                        .addKeyValue("retryScheduled", result.retryScheduled())
                        .addKeyValue("failed", result.failed())
                        .addKeyValue("lostLease", result.lostLease())
                        .log("Completed realtime publication batch");
            }
        } catch (RuntimeException exception) {
            LOGGER.atWarn()
                    .addKeyValue("failureType", exception.getClass().getSimpleName())
                    .log("Realtime publication batch failed safely");
        }
    }
}
