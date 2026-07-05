package com.cayleywcs.application.security;

import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.support.CryptoSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 入站开放接口鉴权（需求 3 / APP KEY 双向）：仅作用于 /open/**。
 * 校验 X-App-Key / X-Timestamp / X-Nonce / X-Sign：应用有效 + 时间戳未过期 + nonce 不重放 + 签名正确。
 * 成功后将 appId/appCode 放入 request attribute 供下游使用；失败返回结构化 ApiResponse.error（HTTP 200）。
 */
@Component
public class AppKeyAuthFilter extends OncePerRequestFilter {
    public static final String ATTR_APP_ID = "wcs.app.id";
    public static final String ATTR_APP_CODE = "wcs.app.code";
    public static final String ATTR_APP_TENANT_ID = "wcs.app.tenant";

    private final ApplicationService applicationService;
    private final AppKeySigner signer;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Long> nonceFallback = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final long maxSkewSeconds;
    private final int maxBodyBytes;

    @org.springframework.beans.factory.annotation.Autowired
    public AppKeyAuthFilter(ApplicationService applicationService,
                            AppKeySigner signer,
                            ObjectProvider<StringRedisTemplate> redisProvider,
                            @Value("${cayleywcs.openapi.appkey-enabled:true}") boolean enabled,
                            @Value("${cayleywcs.openapi.signature-max-skew-seconds:300}") long maxSkewSeconds,
                            @Value("${cayleywcs.openapi.max-body-bytes:1048576}") int maxBodyBytes) {
        this.applicationService = applicationService;
        this.signer = signer;
        this.redisProvider = redisProvider;
        this.enabled = enabled;
        this.maxSkewSeconds = maxSkewSeconds;
        this.maxBodyBytes = Math.max(1024, maxBodyBytes);
    }

    /** 测试便捷构造（默认 1MB body 上限）。 */
    public AppKeyAuthFilter(ApplicationService applicationService, AppKeySigner signer,
                            ObjectProvider<StringRedisTemplate> redisProvider, boolean enabled, long maxSkewSeconds) {
        this(applicationService, signer, redisProvider, enabled, maxSkewSeconds, 1048576);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/open/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        String appKey = request.getHeader("X-App-Key");
        String timestamp = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce");
        String sign = request.getHeader("X-Sign");

        if (isBlank(appKey) || isBlank(timestamp) || isBlank(nonce) || isBlank(sign)) {
            reject(response, ErrorCode.APPKEY_INVALID, "缺少 AppKey 鉴权头");
            return;
        }
        ApplicationEntity app = applicationService.getByAppKey(appKey);
        if (app == null || !Boolean.TRUE.equals(app.getEnabled())) {
            reject(response, ErrorCode.APPKEY_INVALID, "AppKey 无效或应用未启用");
            return;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            reject(response, ErrorCode.APPKEY_REPLAY, "时间戳非法");
            return;
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > maxSkewSeconds) {
            reject(response, ErrorCode.APPKEY_REPLAY, "时间戳过期");
            return;
        }
        if (isReplay(appKey, nonce)) {
            reject(response, ErrorCode.APPKEY_REPLAY, "请求重放");
            return;
        }
        // 签名版本（M13）：缺省 1；应用可设 min_sign_version=2 强制 body 纳签（灰度共存）。
        int signVersion = parseSignVersion(request.getHeader("X-Sign-Version"));
        long minVersion = app.getMin_sign_version() == null ? 1L : app.getMin_sign_version();
        if (signVersion < minVersion) {
            reject(response, ErrorCode.APPKEY_SIGNATURE_INVALID,
                    "签名版本过低(v" + signVersion + ")，该应用要求 >= v" + minVersion);
            return;
        }

        HttpServletRequest downstream = request;
        String expected;
        if (signVersion >= 2) {
            // v2：缓存 body 供本过滤器算摘要 + 下游控制器 @RequestBody 复读。
            // 有界读（对抗审查 P1）：鉴权在读 body 时进行，超 maxBodyBytes 直接拒，防超大 body 打爆堆内存。
            byte[] body = readBounded(request.getInputStream(), maxBodyBytes);
            if (body == null) {
                reject(response, ErrorCode.BAD_REQUEST, "请求体过大(> " + maxBodyBytes + " bytes)");
                return;
            }
            downstream = new CachedBodyHttpServletRequest(request, body);
            String bodyHash = CryptoSupport.sha256Hex(body); // 原始字节，与订阅端一致
            expected = signer.signV2(app.getApp_secret(), appKey, timestamp, nonce,
                    request.getMethod(), request.getRequestURI(), bodyHash);
        } else {
            expected = signer.sign(app.getApp_secret(), appKey, timestamp, nonce,
                    request.getMethod(), request.getRequestURI());
        }
        if (!signer.verify(expected, sign)) {
            reject(response, ErrorCode.APPKEY_SIGNATURE_INVALID, "签名校验失败");
            return;
        }
        downstream.setAttribute(ATTR_APP_ID, app.getId());
        downstream.setAttribute(ATTR_APP_CODE, app.getApp_code());
        downstream.setAttribute(ATTR_APP_TENANT_ID, app.getTenant_id());
        chain.doFilter(downstream, response);
    }

    /** 至多读 max 字节；超过则返回 null（视为过大）。避免 readAllBytes 无上限致 OOM。 */
    private static byte[] readBounded(java.io.InputStream in, int max) throws IOException {
        byte[] buf = new byte[Math.min(max, 8192)];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static int parseSignVersion(String header) {
        if (header == null || header.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(header.trim());
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private boolean isReplay(String appKey, String nonce) {
        String key = "cayleywcs:appkey:nonce:" + appKey + ":" + nonce;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                Boolean first = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(maxSkewSeconds + 5));
                return !Boolean.TRUE.equals(first);
            } catch (RuntimeException ignored) {
                // Redis 不可用时退回内存
            }
        }
        long now = Instant.now().getEpochSecond();
        nonceFallback.entrySet().removeIf(e -> now - e.getValue() > maxSkewSeconds + 5);
        return nonceFallback.putIfAbsent(key, now) != null;
    }

    private void reject(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Void> body = ApiResponse.error(message, code.code());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
