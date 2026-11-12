package com.cayleywcs.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.alarm.entity.AlarmEntity;
import com.cayleywcs.auth.JwtService;
import com.cayleywcs.auth.JwtUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** 真正经过 HTTP Upgrade、Origin 与票据拦截器，再接收报警生命周期帧。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test-flyway")
class MonitorWebSocketIntegrationTest {
    @Autowired private com.cayleywcs.protocol.ProtocolService fixtureProtocols;
    @Autowired private com.cayleywcs.application.mapper.ApplicationMapper fixtureApps;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGIN = "http://127.0.0.1:5184";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort private int port;
    @Autowired private JwtService jwtService;
    @Autowired private MonitorPusher pusher;
    @Autowired private AlarmService alarms;
    @Autowired private MonitorWsTickets tickets;

    @Test
    void ticketIsRequiredSingleUseAndAlarmLifecycleArrivesOverSocket() throws Exception {
        URI bare = URI.create("ws://127.0.0.1:" + port + "/ws/monitor");
        assertRejected(403, () -> open(bare, new Frames()));

        String token = jwtService.generateToken(new JwtUser(1L, "test", "test", "admin", 1L, 1L), 10);
        HttpResponse<String> unauthenticated = ticketRequest(null);
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        HttpResponse<String> issued = ticketRequest(token);
        assertThat(issued.statusCode()).isEqualTo(200);
        String ticket = JSON.readTree(issued.body()).path("data").path("ticket").asText();
        assertThat(ticket).hasSize(43);

        Frames frames = new Frames();
        URI authorized = URI.create(bare + "?ticket=" + ticket);
        WebSocket socket = open(authorized, frames);
        AlarmEntity alarm = null;
        try {
            assertRejected(403, () -> open(authorized, new Frames()));
            pusher.push();
            assertThat(frames.next().path("type").asText()).isEqualTo("monitor");

            com.cayleywcs.support.PublishedDeviceFixtures.ensure(fixtureProtocols,fixtureApps,99777L,"stacker");
            alarm = alarms.raise(99777L, 99777L, "error", "ws-integration", "");
            Long alarmId = alarm.getId();
            pusher.push();
            assertThat(findAlarm(frames.nextWhere(frame -> "raised".equals(
                    findAlarm(frame, alarmId).path("status").asText())), alarmId)
                    .path("status").asText()).isEqualTo("raised");
            alarms.ack(alarmId, "test");
            pusher.push();
            assertThat(findAlarm(frames.nextWhere(frame -> "ack".equals(
                    findAlarm(frame, alarmId).path("status").asText())), alarmId)
                    .path("status").asText()).isEqualTo("ack");
            alarms.clear(alarmId);
            pusher.push();
            assertThat(findAlarm(frames.nextWhere(frame -> findAlarm(frame, alarmId).isMissingNode()),
                    alarmId).isMissingNode()).isTrue();
        } finally {
            if (alarm != null) alarms.clear(alarm.getId());
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void expiredJwtCannotIssueTicketAndForeignOriginCannotConnect() throws Exception {
        String expired = jwtService.generateToken(new JwtUser(1L, "test", "test", "admin", 1L, 1L), -1);
        assertThat(ticketRequest(expired).statusCode()).isEqualTo(401);
        String valid = jwtService.generateToken(new JwtUser(1L, "test", "test", "admin", 1L, 1L), 10);
        String ticket = JSON.readTree(ticketRequest(valid).body()).path("data").path("ticket").asText();
        URI url = URI.create("ws://127.0.0.1:" + port + "/ws/monitor?ticket=" + ticket);
        assertRejected(403, () -> HTTP.newWebSocketBuilder().header("Origin", "https://outside.example")
                .buildAsync(url, new Frames()).join());
    }

    @Test
    void expiredTicketIsRejectedAndAuthorizedSocketClosesAtJwtExpiry() throws Exception {
        URI base = URI.create("ws://127.0.0.1:" + port + "/ws/monitor?ticket=");
        String expiredTicket = tickets.issue(System.currentTimeMillis() + 40).ticket();
        Thread.sleep(60);
        assertRejected(403, () -> open(URI.create(base + expiredTicket), new Frames()));

        String shortSessionTicket = tickets.issue(System.currentTimeMillis() + 500).ticket();
        Frames frames = new Frames();
        open(URI.create(base + shortSessionTicket), frames);
        Thread.sleep(550);
        pusher.push();
        assertThat(frames.closed.poll(3, TimeUnit.SECONDS)).isEqualTo(1008);
    }

    private HttpResponse<String> ticketRequest(String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/monitor/ws-ticket"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static WebSocket open(URI uri, Frames frames) {
        return HTTP.newWebSocketBuilder().header("Origin", ORIGIN)
                .connectTimeout(Duration.ofSeconds(3)).buildAsync(uri, frames).join();
    }

    private static void assertRejected(int status, Runnable connect) {
        assertThatThrownBy(connect::run).isInstanceOfSatisfying(CompletionException.class, error -> {
            assertThat(error.getCause()).isInstanceOf(WebSocketHandshakeException.class);
            assertThat(((WebSocketHandshakeException) error.getCause()).getResponse().statusCode())
                    .isEqualTo(status);
        });
    }

    private static JsonNode findAlarm(JsonNode frame, Long id) {
        for (JsonNode item : frame.path("alarms")) {
            if (item.path("id").asLong() == id) return item;
        }
        return JSON.missingNode();
    }

    private static final class Frames implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<Integer> closed = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.add(statusCode);
            return null;
        }

        JsonNode next() throws Exception {
            String value = messages.poll(3, TimeUnit.SECONDS);
            assertThat(value).isNotNull();
            return JSON.readTree(value);
        }

        JsonNode nextWhere(Predicate<JsonNode> predicate) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                String value = messages.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (value == null) break;
                JsonNode frame = JSON.readTree(value);
                if (predicate.test(frame)) return frame;
            }
            throw new AssertionError("expected monitor frame not received");
        }
    }
}
