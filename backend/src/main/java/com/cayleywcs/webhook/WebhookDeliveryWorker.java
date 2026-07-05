package com.cayleywcs.webhook;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.common.support.CryptoSupport;
import com.cayleywcs.webhook.entity.WebhookDeliveryEntity;
import com.cayleywcs.webhook.entity.WebhookEntity;
import com.cayleywcs.webhook.mapper.WebhookDeliveryMapper;
import com.cayleywcs.webhook.mapper.WebhookMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Webhook 投递 worker（M13）：取到点 pending 投递记录 → POST 到订阅方 URL（HMAC 签名头）→
 * 2xx 置 ok；否则指数退避重投，超 max-attempts 置 failed。
 * SSRF 防护：URL 仅允许 http/https + 可选 host allowlist（cayleywcs.webhook.host-allowlist）。
 * 签名：X-Webhook-Sign = HMAC-SHA256(secret, timestamp + "\n" + nonce + "\n" + sha256Hex(body))。
 */
@Component
public class WebhookDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);
    private static final int SCAN_LIMIT = 100;

    private final WebhookDeliveryMapper deliveryMapper;
    private final WebhookMapper webhookMapper;
    private final HttpClient httpClient;
    private final long maxAttempts;
    private final long backoffBaseMs;
    private final long backoffMaxMs;
    private final Set<String> hostAllowlist;
    private final boolean blockPrivateHosts;

    public WebhookDeliveryWorker(WebhookDeliveryMapper deliveryMapper, WebhookMapper webhookMapper,
                                 @Value("${cayleywcs.webhook.max-attempts:6}") long maxAttempts,
                                 @Value("${cayleywcs.webhook.backoff-base-ms:30000}") long backoffBaseMs,
                                 @Value("${cayleywcs.webhook.backoff-max-ms:1800000}") long backoffMaxMs,
                                 @Value("${cayleywcs.webhook.connect-timeout-ms:5000}") long connectTimeoutMs,
                                 @Value("${cayleywcs.webhook.host-allowlist:}") String hostAllowlist,
                                 @Value("${cayleywcs.webhook.block-private-hosts:false}") boolean blockPrivateHosts) {
        this.deliveryMapper = deliveryMapper;
        this.webhookMapper = webhookMapper;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffBaseMs = Math.max(1000, backoffBaseMs);
        this.backoffMaxMs = Math.max(this.backoffBaseMs, backoffMaxMs);
        this.blockPrivateHosts = blockPrivateHosts;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, connectTimeoutMs)))
                .followRedirects(HttpClient.Redirect.NEVER) // 防重定向绕过 allowlist
                .build();
        this.hostAllowlist = Arrays.stream(hostAllowlist.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Scheduled(initialDelayString = "${cayleywcs.webhook.deliver-interval-ms:2000}",
            fixedDelayString = "${cayleywcs.webhook.deliver-interval-ms:2000}")
    public void tick() {
        runOnce();
    }

    /** 单轮投递（公开供测试驱动）。返回处理条数。 */
    public int runOnce() {
        List<WebhookDeliveryEntity> due = deliveryMapper.selectList(new QueryWrapper<WebhookDeliveryEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"status\"", "pending")
                .le("\"next_retry_time\"", LocalDateTime.now())
                .orderByAsc("\"next_retry_time\"").orderByAsc("\"id\"")
                .last("limit " + SCAN_LIMIT));
        int handled = 0;
        for (WebhookDeliveryEntity d : due) {
            deliverOne(d);
            handled++;
        }
        return handled;
    }

    private void deliverOne(WebhookDeliveryEntity d) {
        WebhookEntity wh = webhookMapper.selectById(d.getWebhook_id());
        if (wh == null || !Boolean.TRUE.equals(wh.getIs_valid()) || !Boolean.TRUE.equals(wh.getEnabled())) {
            markFailed(d, "webhook 不存在/停用");
            return;
        }
        String urlError = validateUrl(wh.getUrl());
        if (urlError != null) {
            markFailed(d, urlError); // SSRF/非法 URL：不重试，直接失败
            return;
        }
        String body = d.getPayload() == null ? "{}" : d.getPayload();
        try {
            HttpResponse<String> resp = post(wh, body);
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                d.setStatus("ok");
                d.setAttempts(d.getAttempts() + 1);
                d.setLast_response("HTTP " + code);
                touch(d);
                deliveryMapper.updateById(d);
                return;
            }
            retryOrFail(d, "HTTP " + code + " " + truncate(resp.body()));
        } catch (Exception ex) {
            retryOrFail(d, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private HttpResponse<String> post(WebhookEntity wh, String body) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = Long.toHexString(ThreadLocalRandom.current().nextLong()) + Long.toHexString(System.nanoTime());
        String sign = CryptoSupport.hmacSha256Hex(wh.getSecret() == null ? "" : wh.getSecret(),
                ts + "\n" + nonce + "\n" + CryptoSupport.sha256Hex(body));
        HttpRequest request = HttpRequest.newBuilder(URI.create(wh.getUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Webhook-Timestamp", ts)
                .header("X-Webhook-Nonce", nonce)
                .header("X-Webhook-Sign", sign)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void retryOrFail(WebhookDeliveryEntity d, String reason) {
        long attempts = d.getAttempts() + 1;
        d.setAttempts(attempts);
        d.setLast_response(truncate(reason));
        if (attempts >= maxAttempts) {
            d.setStatus("failed");
            log.warn("webhook 投递 {} 超最大重试({})，置 failed: {}", d.getId(), maxAttempts, reason);
        } else {
            long backoff = Math.min(backoffMaxMs, backoffBaseMs * (1L << (attempts - 1)));
            d.setNext_retry_time(LocalDateTime.now().plus(Duration.ofMillis(backoff)));
        }
        touch(d);
        deliveryMapper.updateById(d);
    }

    private void markFailed(WebhookDeliveryEntity d, String reason) {
        d.setStatus("failed");
        d.setAttempts(d.getAttempts() + 1);
        d.setLast_response(truncate(reason));
        touch(d);
        deliveryMapper.updateById(d);
    }

    /** URL 校验（SSRF 防护）：仅 http/https；配置 host-allowlist 时 host 必须命中。返回错误信息或 null（合法）。 */
    public String validateUrl(String url) {
        if (url == null || url.isBlank()) {
            return "URL 为空";
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException ex) {
            return "URL 非法: " + ex.getMessage();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "仅允许 http/https，拒绝: " + scheme;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "URL 缺少 host";
        }
        if (!hostAllowlist.isEmpty() && !hostAllowlist.contains(host.toLowerCase(Locale.ROOT))) {
            return "host 不在 allowlist: " + host;
        }
        // 私网/环回封禁（SSRF 纵深，opt-in）：解析成功且命中私网/环回/链路本地即拒；解析失败留给连接阶段。
        // 注：不完全防 DNS rebinding（解析与连接两次解析可能不同）——生产建议配 host-allowlist 白名单为主控。
        if (blockPrivateHosts) {
            try {
                for (InetAddress addr : InetAddress.getAllByName(host)) {
                    if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                            || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                        return "拒绝私网/环回地址: " + host + " → " + addr.getHostAddress();
                    }
                }
            } catch (UnknownHostException ignored) {
                // 解析失败：连接阶段自然失败，不在此拦
            }
        }
        return null;
    }

    private static void touch(WebhookDeliveryEntity d) {
        d.setLast_update_time(LocalDateTime.now());
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 512 ? s : s.substring(0, 512);
    }
}
