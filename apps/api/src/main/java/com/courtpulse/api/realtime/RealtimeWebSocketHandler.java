package com.courtpulse.api.realtime;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public final class RealtimeWebSocketHandler extends TextWebSocketHandler {
    private final RealtimeHub hub;

    public RealtimeWebSocketHandler(RealtimeHub hub) {
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        hub.open(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        hub.subscribe(session, message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.close(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        hub.close(session);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }
}
