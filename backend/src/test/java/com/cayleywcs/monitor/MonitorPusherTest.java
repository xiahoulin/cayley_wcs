package com.cayleywcs.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.alarm.entity.AlarmEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@SpringBootTest
@ActiveProfiles("test-flyway")
class MonitorPusherTest {
    @Autowired private com.cayleywcs.protocol.ProtocolService fixtureProtocols;
    @Autowired private com.cayleywcs.application.mapper.ApplicationMapper fixtureApps;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MonitorPusher pusher;
    @Autowired private MonitorWebSocketHandler handler;
    @Autowired private AlarmService alarms;

    @Test
    void websocketSessionReceivesAlarmLifecycleWithTimeFields() throws Exception {
        List<String> frames = new CopyOnWriteArrayList<>();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(MonitorWsHandshakeInterceptor.AUTHORIZED_UNTIL,
                System.currentTimeMillis() + 60_000));
        doAnswer(invocation -> {
            frames.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        handler.afterConnectionEstablished(session);
        try {
            pusher.push();
            assertThat(latest(frames).get("alarms").isArray()).isTrue();

            com.cayleywcs.support.PublishedDeviceFixtures.ensure(fixtureProtocols,fixtureApps,99997L,"stacker");
            AlarmEntity alarm = alarms.raise(99997L, 99997L, "error", "probe", "");
            pusher.push();
            JsonNode raised = alarmNode(latest(frames), alarm.getId());
            assertThat(raised.get("status").asText()).isEqualTo("raised");
            assertThat(raised.get("raised_time").isTextual()).isTrue();

            alarms.ack(alarm.getId(), "test");
            pusher.push();
            JsonNode acked = alarmNode(latest(frames), alarm.getId());
            assertThat(acked.get("status").asText()).isEqualTo("ack");
            assertThat(acked.get("ack_time").isTextual()).isTrue();

            alarms.clear(alarm.getId());
            pusher.push();
            assertThat(hasAlarm(latest(frames), alarm.getId())).isFalse();
        } finally {
            handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        }
    }

    private static JsonNode latest(List<String> frames) throws Exception {
        assertThat(frames).isNotEmpty();
        return JSON.readTree(frames.get(frames.size() - 1));
    }

    private static JsonNode alarmNode(JsonNode frame, Long id) {
        for (JsonNode alarm : frame.get("alarms")) {
            if (alarm.get("id").asLong() == id) {
                return alarm;
            }
        }
        throw new AssertionError("alarm missing from monitor frame: " + id);
    }

    private static boolean hasAlarm(JsonNode frame, Long id) {
        for (JsonNode alarm : frame.get("alarms")) {
            if (alarm.get("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }
}
