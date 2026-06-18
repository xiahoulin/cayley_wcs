package com.cayleywcs.application.security;

import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.common.exception.ErrorCode;
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

    private final ApplicationService applicationService;
    private final AppKeySigner signer;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Long> nonceFallback = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final long maxSkewSeconds;

    public AppKeyAuthFilter(ApplicationService applicationService,
                            AppKeySigner signer,
                            ObjectProvider<StringRedisTemplate> redisProvider,
                            @Value("${cayleywcs.openapi.appkey-enabled:true}") boolean enabled,
                            @Value("${cayleywcs.openapi.signature-max-skew-seconds:300}") long maxSkewSeconds) {
        this.applicationService = applicationService;
        this.signer = signer;
        this.redisProvider = redisProvider;
        this.enabled = enabled;
        this.maxSkewSeconds = maxSkewSeconds;
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
        String expected = signer.sign(app.getApp_secret(), appKey, timestamp, nonce,
                request.getMethod(), request.getRequestURI());
        if (!signer.verify(expected, sign)) {
            reject(response, ErrorCode.APPKEY_SIGNATURE_INVALID, "签名校验失败");
            return;
        }
        request.setAttribute(ATTR_APP_ID, app.getId());
        request.setAttribute(ATTR_APP_CODE, app.getApp_code());
        chain.doFilter(request, response);
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
