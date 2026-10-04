package com.wagwag.api.realtime;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class RealtimeSocketHandler extends TextWebSocketHandler {
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        session.setTextMessageSizeLimit(256);
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 2000, 16384);
        sessions.put(session.getId(), safe);
        safe.sendMessage(new TextMessage("{\"type\":\"SYNC\"}"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        if ("ping".equals(message.getPayload())) {
            WebSocketSession safe = sessions.get(session.getId());
            if (safe != null) safe.sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
        } else {
            session.close(CloseStatus.NOT_ACCEPTABLE);
        }
    }

    public void send(long petId, String payload) {
        for (WebSocketSession session : sessions.values()) {
            if (!Long.valueOf(petId).equals(session.getAttributes().get("petId"))) continue;
            try { session.sendMessage(new TextMessage(payload)); }
            catch (IOException | RuntimeException error) {
                sessions.remove(session.getId());
                try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException | RuntimeException ignored) { }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session.getId()); }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) throws IOException {
        sessions.remove(session.getId());
        session.close(CloseStatus.SERVER_ERROR);
    }
}
