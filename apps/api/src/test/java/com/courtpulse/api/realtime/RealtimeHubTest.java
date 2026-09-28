package com.courtpulse.api.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.courtpulse.persistence.RealtimeOutboxRecord;
import com.courtpulse.query.CourtPulseQueryService;
import com.courtpulse.query.GameNotFoundException;
import com.courtpulse.query.GameSnapshotReadModel;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class RealtimeHubTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void isolatesGameSubscriptionsAndPublishesOrderedHint() throws Exception {
        CourtPulseQueryService queries = mock(CourtPulseQueryService.class);
        when(queries.game(any())).thenAnswer(invocation -> snapshot(invocation.getArgument(0), 5));
        RealtimeHub hub = hub(queries, 32);
        SessionCapture first = capture("session-1", 2);
        SessionCapture second = capture("session-2", 1);
        hub.open(first.session());
        hub.open(second.session());
        hub.subscribe(first.session(), subscription("game-1", 5));
        hub.subscribe(second.session(), subscription("game-2", 5));

        hub.publish(new RealtimeOutboxRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000006"),
                "GAME_STATE_UPDATED", "game-1", 6, "event-6", null, 1, CLOCK.instant()));

        assertTrue(first.latch().await(1, TimeUnit.SECONDS));
        assertTrue(second.latch().await(1, TimeUnit.SECONDS));
        assertEquals(2, first.messages().size());
        assertEquals(1, second.messages().size());
        assertTrue(first.messages().getLast().contains("\"stateVersion\":6"));
        assertEquals(1, hub.subscriptionsForGame("game-1"));
        hub.close(first.session());
        hub.close(second.session());
    }

    @Test
    void keepAlivePingsOpenSessionsThroughTheirSingleWriter() throws Exception {
        CourtPulseQueryService queries = mock(CourtPulseQueryService.class);
        RealtimeHub hub = hub(queries, 32);
        SessionCapture capture = capture("session-quiet", 0);
        hub.open(capture.session());

        assertEquals(1, hub.keepAlive());

        verify(capture.session(), timeout(1_000)).sendMessage(any(PingMessage.class));
        assertTrue(capture.messages().isEmpty(), "a ping is not a protocol message");
        hub.close(capture.session());
        assertEquals(0, hub.keepAlive());
    }

    @Test
    void initialVersionGapRequestsHttpResynchronization() throws Exception {
        CourtPulseQueryService queries = mock(CourtPulseQueryService.class);
        GameSnapshotReadModel current = snapshot("game-1", 9);
        when(queries.game("game-1")).thenReturn(current);
        RealtimeHub hub = hub(queries, 32);
        SessionCapture capture = capture("session-gap", 2);
        hub.open(capture.session());
        hub.subscribe(capture.session(), subscription("game-1", 5));

        assertTrue(capture.latch().await(1, TimeUnit.SECONDS));
        assertTrue(capture.messages().stream().anyMatch(value -> value.contains("RESYNC_REQUIRED")));
        hub.close(capture.session());
    }

    @Test
    void futureClientVersionIsResetSoLegitimateUpdatesAreDeliveredAfterResync() throws Exception {
        CourtPulseQueryService queries = mock(CourtPulseQueryService.class);
        GameSnapshotReadModel current = snapshot("game-1", 9);
        when(queries.game("game-1")).thenReturn(current);
        RealtimeHub hub = hub(queries, 32);
        SessionCapture capture = capture("session-future", 3);
        hub.open(capture.session());
        hub.subscribe(capture.session(), subscription("game-1", 99));
        hub.publish(record(10));

        assertTrue(capture.latch().await(1, TimeUnit.SECONDS));
        assertTrue(capture.messages().stream().anyMatch(value -> value.contains("RESYNC_REQUIRED")));
        assertTrue(capture.messages().stream().anyMatch(value -> value.contains("\"stateVersion\":10")));
        hub.close(capture.session());
    }

    @Test
    void skipsStaleVersionsButPreservesSameVersionAlertAfterStateHint() throws Exception {
        CourtPulseQueryService queries = mock(CourtPulseQueryService.class);
        GameSnapshotReadModel current = snapshot("game-1", 5);
        when(queries.game("game-1")).thenReturn(current);
        RealtimeHub hub = hub(queries, 32);
        SessionCapture capture = capture("session-order", 3);
        hub.open(capture.session());
        hub.subscribe(capture.session(), subscription("game-1", 5));
        hub.publish(record(4));
        hub.publish(record(6));
        hub.publish(new RealtimeOutboxRecord(
                UUID.randomUUID(), "ALERT_CREATED", "game-1", 6,
                "event-6", "player-ace:10", 1, CLOCK.instant()));

        assertTrue(capture.latch().await(1, TimeUnit.SECONDS));
        assertEquals(3, capture.messages().size());
        assertFalse(capture.messages().stream().anyMatch(value -> value.contains("\"stateVersion\":4")));
        assertTrue(capture.messages().stream().anyMatch(value -> value.contains("GAME_STATE_UPDATED")));
        assertTrue(capture.messages().stream().anyMatch(value -> value.contains("ALERT_CREATED")));
        hub.close(capture.session());
    }

    @Test
    void correctionResyncBypassesOrdinaryVersionSuppression() throws Exception {
        CourtPulseQueryService queries = mock(CourtPulseQueryService.class);
        GameSnapshotReadModel current = snapshot("game-1", 20);
        when(queries.game("game-1")).thenReturn(current);
        RealtimeHub hub = hub(queries, 32);
        SessionCapture capture = capture("session-correction", 3);
        hub.open(capture.session());
        hub.subscribe(capture.session(), subscription("game-1", 20));
        hub.publish(record(21));
        hub.publish(new RealtimeOutboxRecord(UUID.randomUUID(), "RESYNC_REQUIRED",
                "game-1", 20, null, null, 1, CLOCK.instant()));

        assertTrue(capture.latch().await(1, TimeUnit.SECONDS));
        assertTrue(capture.messages().stream().anyMatch(value ->
                value.contains("\"messageType\":\"RESYNC_REQUIRED\"")
                        && value.contains("\"code\":\"game_correction\"")));
        assertFalse(capture.messages().stream().anyMatch(value -> value.contains("ownerSubject")));
        hub.close(capture.session());
    }

    @Test
    void unknownGameReturnsSanitizedProtocolProblem() throws Exception {
        CourtPulseQueryService queries = mock(CourtPulseQueryService.class);
        when(queries.game("missing")).thenThrow(new GameNotFoundException("missing"));
        RealtimeHub hub = hub(queries, 32);
        SessionCapture capture = capture("session-missing", 1);
        hub.open(capture.session());
        hub.subscribe(capture.session(), subscription("missing", 0));

        assertTrue(capture.latch().await(1, TimeUnit.SECONDS));
        assertTrue(capture.messages().getFirst().contains("game_not_found"));
        assertTrue(!capture.messages().getFirst().toLowerCase().contains("exception"));
        hub.close(capture.session());
    }

    @Test
    void disconnectsSlowClientWhenBoundedOutboundBufferIsExhausted() throws Exception {
        CourtPulseQueryService queries = mock(CourtPulseQueryService.class);
        GameSnapshotReadModel current = snapshot("game-1", 0);
        when(queries.game("game-1")).thenReturn(current);
        RealtimeHub hub = hub(queries, 1);
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicBoolean open = new AtomicBoolean(true);
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        when(session.getId()).thenReturn("slow-session");
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        doAnswer(invocation -> {
            writerEntered.countDown();
            releaseWriter.await(1, TimeUnit.SECONDS);
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        doAnswer(invocation -> {
            open.set(false);
            closed.countDown();
            return null;
        }).when(session).close(any(CloseStatus.class));
        hub.open(session);
        hub.subscribe(session, subscription("game-1", 0));
        assertTrue(writerEntered.await(1, TimeUnit.SECONDS));

        hub.publish(record(1));
        hub.publish(record(2));

        assertTrue(closed.await(1, TimeUnit.SECONDS));
        assertEquals(0, hub.activeSessions());
        releaseWriter.countDown();
    }

    private static RealtimeHub hub(CourtPulseQueryService queries, int bufferSize) {
        return new RealtimeHub(
                new RealtimeProtocol(JsonMapper.builder().addModule(new JavaTimeModule()).build()),
                queries,
                CLOCK,
                new SimpleMeterRegistry(),
                10,
                bufferSize);
    }

    private static String subscription(String gameId, long version) {
        return "{\"schemaVersion\":1,\"messageType\":\"SUBSCRIBE\",\"gameId\":\""
                + gameId + "\",\"lastStateVersion\":" + version + "}";
    }

    private static GameSnapshotReadModel snapshot(String gameId, long version) {
        GameSnapshotReadModel snapshot = mock(GameSnapshotReadModel.class);
        when(snapshot.gameId()).thenReturn(gameId);
        when(snapshot.stateVersion()).thenReturn(version);
        return snapshot;
    }

    private static SessionCapture capture(String id, int expectedMessages) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        List<String> messages = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(expectedMessages);
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0, TextMessage.class).getPayload());
            latch.countDown();
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return new SessionCapture(session, messages, latch);
    }

    private static RealtimeOutboxRecord record(long version) {
        return new RealtimeOutboxRecord(
                UUID.randomUUID(), "GAME_STATE_UPDATED", "game-1", version,
                "event-" + version, null, 1, CLOCK.instant());
    }

    private record SessionCapture(
            WebSocketSession session, List<String> messages, CountDownLatch latch) {}
}
