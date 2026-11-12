package com.cayleywcs.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cayleywcs.auth.JwtService;
import com.cayleywcs.auth.JwtUser;
import com.cayleywcs.task.TaskEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** 指标端点受 JWT 保护；实际请求与调度轮次会生成固定标签指标。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoints.web.exposure.include=health,metrics")
@ActiveProfiles("test-flyway")
class WcsMetricsIntegrationTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort private int port;
    @Autowired private JwtService jwtService;
    @Autowired private TaskEngine taskEngine;
    @Autowired private WebhookBacklogMetrics backlog;

    @Test
    void metricsAreAuthenticatedAndHaveOnlyFixedOperationAndJobLabels() throws Exception {
        String token = jwtService.generateToken(new JwtUser(1L, "test", "test", "admin", 1L, 1L), 10);
        assertThat(get("/actuator/metrics", null).statusCode()).isEqualTo(401);

        HttpRequest stats = HttpRequest.newBuilder(url("/stats/task-summary"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"appId\":99779,\"fromMillis\":0,\"toMillis\":0}"))
                .build();
        assertThat(HTTP.send(stats, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        taskEngine.tick();
        backlog.sample();

        assertMeasurement(token, "/actuator/metrics/wcs.query.http.duration?tag=operation:task-summary");
        assertMeasurement(token, "/actuator/metrics/wcs.scheduler.duration?tag=job:task-engine");
        assertMeasurement(token, "/actuator/metrics/wcs.webhook.pending");
        assertMeasurement(token, "/actuator/metrics/wcs.task.worker.active");
    }

    private void assertMeasurement(String token, String path) throws Exception {
        HttpResponse<String> response = get(path, token);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode measurements = JSON.readTree(response.body()).path("measurements");
        assertThat(measurements.isArray()).isTrue();
        assertThat(measurements.size()).isPositive();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(url(path)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
