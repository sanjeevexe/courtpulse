package com.courtpulse.api.realtime;

import org.springframework.scheduling.annotation.Scheduled;

/** Keeps quiet public subscriptions open through idle-timeout proxies between game events. */
public final class RealtimeKeepAliveScheduler {
    private final RealtimeHub hub;

    public RealtimeKeepAliveScheduler(RealtimeHub hub) {
        this.hub = hub;
    }

    @Scheduled(fixedRateString = "${courtpulse.realtime.keepalive-interval}")
    public void keepAlive() {
        hub.keepAlive();
    }
}
