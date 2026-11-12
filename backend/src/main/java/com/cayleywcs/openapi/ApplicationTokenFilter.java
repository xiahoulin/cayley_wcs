package com.cayleywcs.openapi;

import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.security.AppKeyAuthFilter;
import com.cayleywcs.application.security.CachedBodyHttpServletRequest;
import com.cayleywcs.auth.JwtUser;
import com.cayleywcs.common.exception.WcsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** 对外 POST JSON 封装与应用认证；管理端响应和用户登录保持独立。 */
@Component
public class ApplicationTokenFilter extends OncePerRequestFilter {
    private static final Set<String> TOKEN_FIELDS = Set.of("app_id", "app_key", "app_secret", "grant_type", "request_id");
    private static final Set<String> BUSINESS_FIELDS = Set.of("app_id", "app_key", "access_token", "request_id", "data");
    private final ApplicationTokenService tokens;
    private final ObjectMapper json = new ObjectMapper();

    public ApplicationTokenFilter(ApplicationTokenService tokens) { this.tokens = tokens; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/open/") && !"/system/token".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String requestId = UUID.randomUUID().toString();
        var previous = SecurityContextHolder.getContext().getAuthentication();
        ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(response);
        try {
            if (!"POST".equals(request.getMethod())) { write(response, 100003, "接口必须使用 POST", requestId, null); return; }
            if (!isJson(request.getContentType())) {
                write(response, 100002, "Content-Type 必须为 application/json", requestId, null); return;
            }
            byte[] bytes = request.getInputStream().readNBytes(1048577);
            if (bytes.length > 1048576) { write(response, 100009, "请求体超过 1 MiB", requestId, null); return; }
            JsonNode body;
            try { body = json.readTree(bytes); } catch (IOException ex) { write(response, 100001, "JSON 格式错误", requestId, null); return; }
            if (body == null || !body.isObject()) { write(response, 100001, "请求体必须为 JSON 对象", requestId, null); return; }
            if (body.has("request_id")) {
                if (!body.path("request_id").isTextual() || body.path("request_id").asText().isBlank() || body.path("request_id").asText().length() > 64) {
                    write(response, 100006, "request_id 必须为 1 至 64 字符的字符串", requestId, null); return;
                }
                requestId = body.path("request_id").asText();
            }
            boolean tokenRoute = "/system/token".equals(request.getRequestURI());
            Set<String> fields = tokenRoute ? TOKEN_FIELDS : BUSINESS_FIELDS;
            var names = body.fieldNames();
            while (names.hasNext()) {
                if (!fields.contains(names.next())) { write(response, 100006, "请求含未声明的公共字段", requestId, null); return; }
            }
            for (String field : tokenRoute ? List.of("app_id", "app_key", "app_secret", "grant_type") : List.of("app_id", "app_key")) {
                if (!body.path(field).isTextual() || body.path(field).asText().isBlank()) { write(response, 100004, field + " 必须为非空字符串", requestId, null); return; }
            }
            HttpServletRequest downstream = new CachedBodyHttpServletRequest(request, bytes);
            if (!tokenRoute) {
                if (!body.path("access_token").isTextual() && !body.path("access_token").isMissingNode()) { write(response, 100005, "access_token 必须为字符串", requestId, null); return; }
                ApplicationEntity app = tokens.validate(body.path("app_id").asText(), body.path("app_key").asText(), body.path("access_token").asText());
                if (!body.path("data").isObject()) { write(response, 100005, "data 必须为对象", requestId, null); return; }
                if (containsPrivateField(body.path("data"))) { write(response, 100006, "业务数据含未声明的归属字段", requestId, null); return; }
                downstream = new CachedBodyHttpServletRequest(request, json.writeValueAsBytes(body.path("data")));
                downstream.setAttribute(AppKeyAuthFilter.ATTR_APP_ID, app.getId());
                downstream.setAttribute(AppKeyAuthFilter.ATTR_APP_CODE, app.getApp_code());
                downstream.setAttribute(AppKeyAuthFilter.ATTR_APP_TENANT_ID, app.getTenant_id());
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        new JwtUser(0L, app.getApp_code(), app.getApp_name(), "application", app.getTenant_id(), 0L), null, List.of()));
            }
            chain.doFilter(downstream, cached);
            JsonNode result = json.readTree(cached.getContentAsByteArray());
            int code = result != null && result.path("isSuccess").asBoolean(false) ? 0 : (result == null ? 500 : result.path("code").asInt(500));
            JsonNode data = result == null ? null : result.get("data");
            stripPrivateFields(data);
            write(response, code, code == 0 ? "成功" : result == null ? "服务器内部错误" : result.path("errorMessage").asText("请求失败"), requestId, data);
        } catch (WcsException ex) {
            write(response, ex.getCode(), ex.getMessage(), requestId, null);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private boolean containsPrivateField(JsonNode node) {
        if (node.isObject() && (node.has("tenant_id") || node.has("tenantId"))) { return true; }
        for (JsonNode child : node) { if (containsPrivateField(child)) { return true; } }
        return false;
    }

    private static boolean isJson(String contentType) {
        try {
            var media = org.springframework.http.MediaType.parseMediaType(contentType == null ? "" : contentType);
            return "application".equalsIgnoreCase(media.getType()) && "json".equalsIgnoreCase(media.getSubtype());
        } catch (IllegalArgumentException ex) { return false; }
    }

    private void stripPrivateFields(JsonNode node) {
        if (node == null) { return; }
        if (node instanceof ObjectNode object) { object.remove(List.of("tenant_id", "tenantId", "app_secret")); }
        for (JsonNode child : node) { stripPrivateFields(child); }
    }

    private void write(HttpServletResponse response, int code, String message, String requestId, JsonNode data) throws IOException {
        ObjectNode result = json.createObjectNode();
        result.put("errcode", code); result.put("errmsg", message); result.put("request_id", requestId);
        result.put("timestamp", Instant.now().toString()); result.set("data", data == null ? json.nullNode() : data);
        response.setStatus(200); response.setContentType("application/json;charset=UTF-8");
        byte[] bytes = json.writeValueAsBytes(result);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
}
