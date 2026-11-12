package com.cayleywcs.monitor;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** 实时监控 WebSocket：维护订阅会话，向所有客户端广播设备状态/报警快照。 */
@Component
public class MonitorWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(MonitorWebSocketHandler.class);
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (expired(session)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcast(String json) {
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            if (expired(session)) {
                sessions.remove(session);
                try {
                    session.close(CloseStatus.POLICY_VIOLATION);
                } catch (IOException ex) {
                    log.debug("ws expiry close failed: {}", ex.getMessage());
                }
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException ex) {
                log.debug("ws send failed: {}", ex.getMessage());
                sessions.remove(session);
            }
        }
    }

    public int sessionCount() {
        return sessions.size();
    }

    private static boolean expired(WebSocketSession session) {
        Object until = session.getAttributes().get(MonitorWsHandshakeInterceptor.AUTHORIZED_UNTIL);
        return !(until instanceof Long time) || time <= System.currentTimeMillis();
    }
}
