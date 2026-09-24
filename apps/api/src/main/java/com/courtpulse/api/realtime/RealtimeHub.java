package com.courtpulse.api.realtime;

import com.courtpulse.persistence.RealtimeOutboxRecord;
import com.courtpulse.query.CourtPulseQueryService;
import com.courtpulse.query.GameNotFoundException;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

public final class RealtimeHub {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeHub.class);

    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger subscriptionCount = new AtomicInteger();
    private final RealtimeProtocol protocol;
    private final CourtPulseQueryService queries;
    private final Clock clock;
    private final int sessionLimit;
    private final int outboundBufferSize;
    private final Counter published;
    private final Counter staleOrDuplicate;
    private final Counter resyncs;
    private final Counter slowDisconnects;

    public RealtimeHub(
            RealtimeProtocol protocol,
            CourtPulseQueryService queries,
            Clock clock,
            MeterRegistry meters,
            int sessionLimit,
            int outboundBufferSize) {
        if (sessionLimit < 1 || outboundBufferSize < 1) {
            throw new IllegalArgumentException("Realtime session and buffer limits must be positive");
        }
        this.protocol = protocol;
        this.queries = queries;
        this.clock = clock;
        this.sessionLimit = sessionLimit;
        this.outboundBufferSize = outboundBufferSize;
        this.published = meters.counter("courtpulse.realtime.messages.published");
        this.staleOrDuplicate = meters.counter("courtpulse.realtime.messages.stale_or_duplicate");
        this.resyncs = meters.counter("courtpulse.realtime.resync.requested");
        this.slowDisconnects = meters.counter("courtpulse.realtime.slow_client.disconnected");
        Gauge.builder("courtpulse.realtime.sessions.active", sessions, ConcurrentHashMap::size)
                .register(meters);
        Gauge.builder("courtpulse.realtime.subscriptions.active", subscriptionCount, AtomicInteger::get)
                .tag("scope", "game")
                .register(meters);
    }

    public boolean open(WebSocketSession session) throws IOException {
        if (sessions.size() >= sessionLimit) {
            session.close(CloseStatus.SERVICE_OVERLOAD.withReason("session limit reached"));
            return false;
        }
        session.setTextMessageSizeLimit(4_096);
        ClientSession client = new ClientSession(session, outboundBufferSize);
        if (sessions.putIfAbsent(session.getId(), client) != null) {
            return false;
        }
        client.startWriter();
        return true;
    }

    public void close(WebSocketSession session) {
        ClientSession removed = sessions.remove(session.getId());
        if (removed != null) {
            if (removed.gameId != null) {
                subscriptionCount.decrementAndGet();
            }
            removed.stop();
        }
    }

    public void subscribe(WebSocketSession session, String payload) {
        ClientSession client = sessions.get(session.getId());
        if (client == null) {
            return;
        }
        try {
            RealtimeSubscription subscription = protocol.decodeSubscription(payload);
            long currentVersion = queries.game(subscription.gameId()).stateVersion();
            if (client.gameId == null) {
                subscriptionCount.incrementAndGet();
            }
            client.gameId = subscription.gameId();
            long suppliedVersion = subscription.lastStateVersion() == null
                    ? currentVersion
                    : subscription.lastStateVersion();
            client.highestStateVersion = suppliedVersion;
            client.offer(message(
                    "SUBSCRIPTION_ACKNOWLEDGED",
                    subscription.gameId(),
                    currentVersion,
                    null,
                    null,
                    null,
                    null));
            if (suppliedVersion != currentVersion) {
                requestResync(client, currentVersion, "version_gap");
                // A claimed future version must not suppress legitimate updates after the
                // client has been told to refresh from the authoritative HTTP snapshot.
                client.highestStateVersion = currentVersion;
            }
            LOGGER.atInfo()
                    .addKeyValue("sessionId", session.getId())
                    .addKeyValue("gameId", subscription.gameId())
                    .addKeyValue("stateVersion", currentVersion)
                    .log("Realtime game subscription established");
        } catch (GameNotFoundException exception) {
            client.offer(message("PROBLEM", "unknown", null, null, null, "game_not_found",
                    "The requested game is not available"));
        } catch (RealtimeProtocolException exception) {
            client.offer(message("PROBLEM", "unknown", null, null, null, exception.code(),
                    exception.getMessage()));
        }
    }

    public int publish(RealtimeOutboxRecord record) {
        boolean correctionResync = "RESYNC_REQUIRED".equals(record.eventType());
        RealtimeServerMessage message = message(
                record.eventType(),
                record.gameId(),
                record.stateVersion(),
                record.eventId(),
                record.triggerKey(),
                correctionResync ? "game_correction" : null,
                correctionResync ? "Refresh the authoritative HTTP resources" : null,
                record.outboxId().toString());
        int delivered = 0;
        for (ClientSession client : sessions.values()) {
            if (!record.gameId().equals(client.gameId)) {
                continue;
            }
            if (!correctionResync && record.stateVersion() < client.highestStateVersion) {
                staleOrDuplicate.increment();
                continue;
            }
            if (client.offer(message)) {
                client.highestStateVersion = Math.max(client.highestStateVersion, record.stateVersion());
                if (correctionResync) resyncs.increment();
                delivered++;
            }
        }
        published.increment();
        LOGGER.atInfo()
                .addKeyValue("messageId", record.outboxId())
                .addKeyValue("gameId", record.gameId())
                .addKeyValue("stateVersion", record.stateVersion())
                .addKeyValue("messageType", record.eventType())
                .addKeyValue("subscribers", delivered)
                .log("Published realtime hint");
        return delivered;
    }

    public int activeSessions() {
        return sessions.size();
    }

    public long subscriptionsForGame(String gameId) {
        return sessions.values().stream().filter(client -> gameId.equals(client.gameId)).count();
    }

    @PreDestroy
    public void shutdown() {
        for (ClientSession client : List.copyOf(sessions.values())) {
            try {
                client.socket.close(CloseStatus.GOING_AWAY.withReason("server shutdown"));
            } catch (IOException ignored) {
                LOGGER.debug("Socket already closed during realtime shutdown");
            }
            close(client.socket);
        }
    }

    private void requestResync(ClientSession client, long currentVersion, String code) {
        resyncs.increment();
        client.offer(message("RESYNC_REQUIRED", client.gameId, currentVersion, null, null, code,
                "Refresh the authoritative HTTP resources"));
    }

    private RealtimeServerMessage message(
            String type,
            String gameId,
            Long stateVersion,
            String eventId,
            String triggerKey,
            String code,
            String detail) {
        return message(type, gameId, stateVersion, eventId, triggerKey, code, detail,
                UUID.randomUUID().toString());
    }

    private RealtimeServerMessage message(
            String type,
            String gameId,
            Long stateVersion,
            String eventId,
            String triggerKey,
            String code,
            String detail,
            String messageId) {
        Instant emittedAt = clock.instant();
        return new RealtimeServerMessage(
                RealtimeServerMessage.SCHEMA_VERSION,
                type,
                messageId,
                gameId,
                stateVersion,
                emittedAt,
                messageId,
                eventId,
                triggerKey,
                code,
                detail);
    }

    private final class ClientSession {
        private final WebSocketSession socket;
        private final ArrayBlockingQueue<String> outbound;
        private volatile String gameId;
        private volatile long highestStateVersion;
        private volatile boolean running = true;
        private Thread writer;

        private ClientSession(WebSocketSession socket, int capacity) {
            this.socket = socket;
            this.outbound = new ArrayBlockingQueue<>(capacity);
        }

        private void startWriter() {
            writer = Thread.ofVirtual().name("courtpulse-ws-" + socket.getId()).start(() -> {
                while (running && socket.isOpen()) {
                    try {
                        String payload = outbound.take();
                        socket.sendMessage(new TextMessage(payload));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (IOException exception) {
                        close(socket);
                        return;
                    }
                }
            });
        }

        private boolean offer(RealtimeServerMessage message) {
            if (!running || !socket.isOpen()) {
                return false;
            }
            if (outbound.offer(protocol.encode(message))) {
                return true;
            }
            slowDisconnects.increment();
            try {
                socket.close(CloseStatus.POLICY_VIOLATION.withReason("outbound buffer exhausted"));
            } catch (IOException ignored) {
                LOGGER.debug("Socket already closed while enforcing realtime buffer bound");
            }
            close(socket);
            return false;
        }

        private void stop() {
            running = false;
            if (writer != null) {
                writer.interrupt();
            }
        }
    }
}
