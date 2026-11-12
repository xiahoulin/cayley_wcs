package com.cayleywcs.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test-flyway")
class ApplicationTokenIntegrationTest {
    @LocalServerPort int port;
    @Autowired ApplicationService apps;
    @Autowired JdbcTemplate jdbc;
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    ApplicationEntity app() { return apps.listAll().stream().filter(a -> "WMS-MAIN".equals(a.getApp_code())).findFirst().orElseThrow(); }
    JsonNode issue(ApplicationEntity a) throws Exception {
        return call("/system/token", Map.of("app_id", a.getId().toString(), "app_key", a.getApp_key(), "app_secret", a.getApp_secret(), "grant_type", "client_credentials"));
    }
    Map<String,Object> envelope(ApplicationEntity a, String token, Object data) {
        return Map.of("app_id", a.getId().toString(), "app_key", a.getApp_key(), "access_token", token, "request_id", "token-test", "data", data);
    }
    JsonNode call(String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    @Test void issuedTokensCoexistAndProtectedQueryUsesPublicEnvelope() throws Exception {
        var a = app(); var first = issue(a); var second = issue(a);
        assertThat(first.path("errcode").asInt()).isZero();
        assertThat(first.path("data").path("expires_in").asLong()).isEqualTo(7200);
        assertThat(first.path("data").has("app_secret")).isFalse();
        for (var token : new String[]{first.path("data").path("access_token").asText(), second.path("data").path("access_token").asText()}) {
            var response = call("/open/task/query", envelope(a, token, Map.of("sinceMillis", 0, "limit", 10)));
            assertThat(response.path("errcode").asInt()).isZero();
            assertThat(response.path("request_id").asText()).isEqualTo("token-test");
            assertThat(response.toString()).doesNotContain("tenant_id");
            assertThat(response.has("isSuccess")).isFalse();
        }
    }

    @Test void invalidExpiredAndRevokedCredentialsAreRejected() throws Exception {
        var a = app(); var issued = issue(a); var token = issued.path("data").path("access_token").asText();
        assertThat(call("/open/task/query", envelope(a, "invalid", Map.of())).path("errcode").asInt()).isEqualTo(102002);
        jdbc.update("update wcs_open_token set expires_at=0 where app_id=?", a.getId());
        assertThat(call("/open/task/query", envelope(a, token, Map.of())).path("errcode").asInt()).isEqualTo(102003);
        token = issue(a).path("data").path("access_token").asText();
        try {
            jdbc.update("update wcs_application set app_secret=? where id=?", "rotated-test-secret", a.getId());
            assertThat(call("/open/task/query", envelope(a, token, Map.of())).path("errcode").asInt()).isEqualTo(102004);
        } finally { jdbc.update("update wcs_application set app_secret=? where id=?", a.getApp_secret(), a.getId()); }
    }

    @Test void tokenCannotChangeIdentityOrOverrideOwnershipAndLegacyHeadersDoNotBypass() throws Exception {
        var a = app(); var token = issue(a).path("data").path("access_token").asText();
        var other = apps.listAll().stream().filter(x -> "STACKER01".equals(x.getApp_code())).findFirst().orElseThrow();
        assertThat(call("/open/task/query", envelope(other, token, Map.of())).path("errcode").asInt()).isEqualTo(102005);
        assertThat(call("/open/task/query", envelope(a, token, Map.of("payload", Map.of("tenant_id", 2)))).path("errcode").asInt()).isEqualTo(100006);
        assertThat(call("/open/task/query", Map.of("appId", other.getId())).path("errcode").asInt()).isEqualTo(100006);
        var ownToken = issue(other).path("data").path("access_token").asText();
        assertThat(call("/open/task/query", envelope(other, ownToken, Map.of("appId", a.getId(), "sinceMillis", 0, "limit", 10))).path("errcode").asInt()).isEqualTo(403);
    }

    @Test void credentialAndGrantErrorsAreSpecific() throws Exception {
        var a = app();
        assertThat(call("/system/token", Map.of("app_id", a.getId().toString(), "app_key", a.getApp_key(), "app_secret", "wrong", "grant_type", "client_credentials")).path("errcode").asInt()).isEqualTo(101003);
        assertThat(call("/system/token", Map.of("app_id", a.getId().toString(), "app_key", a.getApp_key(), "app_secret", a.getApp_secret(), "grant_type", "password")).path("errcode").asInt()).isEqualTo(102006);
    }

    @Test void publicRequestMethodMediaTypeAndMissingTokenAreChecked() throws Exception {
        var url = URI.create("http://127.0.0.1:" + port + "/system/token");
        var get = HTTP.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(JSON.readTree(get.body()).path("errcode").asInt()).isEqualTo(100003);
        var invalidType = HTTP.send(HttpRequest.newBuilder(url).header("Content-Type", "application/jsonp")
                .POST(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(JSON.readTree(invalidType.body()).path("errcode").asInt()).isEqualTo(100002);
        var a = app();
        assertThat(call("/open/task/query", Map.of("app_id", a.getId().toString(), "app_key", a.getApp_key(), "data", Map.of("sinceMillis", 0, "limit", 10)))
                .path("errcode").asInt()).isEqualTo(102001);
    }
}
