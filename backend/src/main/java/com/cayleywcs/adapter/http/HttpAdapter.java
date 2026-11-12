package com.cayleywcs.adapter.http;

import com.cayleywcs.adapter.AbstractProtocolAdapter;
import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolException;
import com.cayleywcs.adapter.message.PointMessage;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** HTTP 设备点位读写均 POST JSON；应用 token 颁发由开放 API 层处理。 */
public class HttpAdapter extends AbstractProtocolAdapter {
    private HttpClient http;
    private String baseUrl;
    private long timeoutMs;
    private int maxResponseBytes;

    @Override public String protocolType() { return "http"; }

    @Override protected void doConnect(AdapterContext ctx) throws Exception {
        baseUrl = ctx.connParam("baseUrl", "http://127.0.0.1").replaceAll("/+$", "");
        timeoutMs = ctx.connParamLong("requestTimeoutMs", 5000);
        maxResponseBytes = Math.toIntExact(ctx.connParamLong("maxResponseBytes", 1048576));
        if (timeoutMs <= 0 || maxResponseBytes <= 0 || maxResponseBytes > 8388608) {
            throw new IllegalArgumentException("HTTP 超时或响应长度上限无效");
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        String path = ctx.connParam("healthPath", "");
        if (!path.isBlank()) {
            var response = http.send(HttpRequest.newBuilder(address(path)).timeout(Duration.ofMillis(timeoutMs))
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
            checkStatus(response.statusCode());
        }
    }

    @Override protected void doDisconnect() { http = null; }
    @Override public boolean heartbeat() { return isConnected(); }

    @Override public Object read(ProtocolPointEntity point) {
        requireConnected();
        return decodeResponse(point, exchange(point,
                PointMessage.request("read", point.getField_name(), null)));
    }

    @Override public void write(ProtocolPointEntity point, Object value) {
        requireConnected();
        Object encoded = encodeValue(point, value);
        Object returned = exchange(point, PointMessage.request("write", point.getField_name(), encoded));
        verifyWriteEcho(point, encoded, returned);
    }

    private Object exchange(ProtocolPointEntity point, PointMessage message) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(address(point.getAddress()))
                    .timeout(Duration.ofMillis(timeoutMs)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(message.bytes()))
                    .build();
            pending = http.sendAsync(request, information -> new LimitedBody(maxResponseBytes));
            // Includes the complete response body, so slow chunked bodies cannot extend the operation indefinitely.
            var response = pending.get(timeoutMs, TimeUnit.MILLISECONDS);
            checkStatus(response.statusCode());
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (!type.toLowerCase().startsWith("application/json")) {
                throw new ProtocolException(4225,"HTTP 点位响应 Content-Type 必须为 application/json");
            }
            return PointMessage.parse(response.body()).responseValue(message, point.getField_name());
        } catch (Exception ex) {
            if (pending != null) { pending.cancel(true); }
            throw ProtocolException.io(point.getField_name(), ex);
        }
    }

    private URI address(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("HTTP 点位地址必须为以 / 开头的相对路径");
        }
        return URI.create(baseUrl + path);
    }

    private static void checkStatus(int status) {
        if (status < 200 || status >= 300) { throw new ProtocolException(4222,"HTTP 设备返回错误状态：" + status); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return completed; }
        @Override public void onSubscribe(Flow.Subscription next) { subscription=next; next.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer part : buffers) {
                if (bytes.size()+part.remaining()>limit) {
                    subscription.cancel(); completed.completeExceptionally(new ProtocolException(4225,"HTTP 点位响应超过长度上限"));
                    return;
                }
                byte[] chunk=new byte[part.remaining()]; part.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { completed.completeExceptionally(error); }
        @Override public void onComplete() { completed.complete(bytes.toByteArray()); }
    }
}
