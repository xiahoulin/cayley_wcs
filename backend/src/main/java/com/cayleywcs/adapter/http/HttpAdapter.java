package com.cayleywcs.adapter.http;

import com.cayleywcs.adapter.AbstractProtocolAdapter;
import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP/REST 适配器（JDK HttpClient）。point.address 为相对路径；读=GET，写=POST。
 * conn_params：baseUrl / requestTimeoutMs / healthPath。
 */
public class HttpAdapter extends AbstractProtocolAdapter {
    private HttpClient http;
    private String baseUrl;
    private long timeoutMs;

    @Override
    public String protocolType() {
        return "http";
    }

    @Override
    protected void doConnect(AdapterContext ctx) throws Exception {
        this.baseUrl = stripTrailingSlash(ctx.connParam("baseUrl", "http://127.0.0.1"));
        this.timeoutMs = ctx.connParamLong("requestTimeoutMs", 5000);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        String healthPath = ctx.connParam("healthPath", "");
        if (!healthPath.isBlank()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + healthPath))
                    .timeout(Duration.ofMillis(timeoutMs)).GET().build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Override
    protected void doDisconnect() {
        this.http = null;
    }

    @Override
    public boolean heartbeat() {
        return isConnected();
    }

    @Override
    public Object read(ProtocolPointEntity point) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + point.getAddress()))
                    .timeout(Duration.ofMillis(timeoutMs)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception ex) {
            throw new RuntimeException("HTTP GET failed: " + point.getAddress(), ex);
        }
    }

    @Override
    public void write(ProtocolPointEntity point, Object value) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + point.getAddress()))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(String.valueOf(value))).build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new RuntimeException("HTTP POST failed: " + point.getAddress(), ex);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
