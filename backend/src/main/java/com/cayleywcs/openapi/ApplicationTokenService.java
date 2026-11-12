package com.cayleywcs.openapi;

import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.CryptoSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 应用令牌持久化仅保存摘要；每次验证重新检查应用状态、密钥与固定归属。 */
@Service
public class ApplicationTokenService {
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ApplicationService applications;
    private final JdbcTemplate jdbc;
    private final long ttl;

    public ApplicationTokenService(ApplicationService applications, JdbcTemplate jdbc,
            @Value("${cayleywcs.openapi.token-seconds:7200}") long ttl) {
        this.applications = applications;
        this.jdbc = jdbc;
        this.ttl = Math.max(60, ttl);
    }

    @Transactional
    public TokenData issue(TokenRequest request) {
        if(lifecycle!=null) lifecycle.references();
        if (!"client_credentials".equals(request.grant_type())) {
            throw new WcsException(ErrorCode.OPEN_GRANT_INVALID, "grant_type 必须为 client_credentials");
        }
        ApplicationEntity app = credentials(request.app_id(), request.app_key());
        if (!equal(app.getApp_secret(), request.app_secret())) {
            throw new WcsException(ErrorCode.OPEN_SECRET_INVALID, "应用密钥无效");
        }
        long now = Instant.now().getEpochSecond();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("delete from wcs_open_token where expires_at < ?", now - 86400);
        jdbc.update("insert into wcs_open_token(token_hash,app_id,tenant_id,credential_hash,issued_at,expires_at) values(?,?,?,?,?,?)",
                hash(token), app.getId(), app.getTenant_id(), hash(app.getApp_secret()), now, now + ttl);
        return new TokenData(String.valueOf(app.getId()), app.getApp_key(), token, ttl,
                Instant.ofEpochSecond(now).toString(), Instant.ofEpochSecond(now + ttl).toString());
    }

    public ApplicationEntity validate(String appId, String appKey, String token) {
        if (token == null || token.isBlank()) {
            throw new WcsException(ErrorCode.OPEN_TOKEN_MISSING, "access_token 必传");
        }
        List<TokenRow> rows = jdbc.query("select app_id,tenant_id,credential_hash,expires_at from wcs_open_token where token_hash=?",
                (rs, n) -> new TokenRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4)), hash(token));
        if (rows.isEmpty()) {
            throw new WcsException(ErrorCode.OPEN_TOKEN_INVALID, "access_token 无效");
        }
        TokenRow row = rows.getFirst();
        if (row.expiresAt() <= Instant.now().getEpochSecond()) {
            throw new WcsException(ErrorCode.OPEN_TOKEN_EXPIRED, "access_token 已过期");
        }
        ApplicationEntity app = applications.getByAppKey(appKey);
        if (app == null || !String.valueOf(row.appId()).equals(appId) || !app.getId().equals(row.appId())) {
            throw new WcsException(ErrorCode.OPEN_TOKEN_MISMATCH, "令牌与应用不匹配");
        }
        if (!Boolean.TRUE.equals(app.getEnabled()) || !Boolean.TRUE.equals(app.getIs_valid())
                || !java.util.Objects.equals(app.getTenant_id(), row.tenantId())
                || !equal(hash(app.getApp_secret()), row.credentialHash())) {
            throw new WcsException(ErrorCode.OPEN_TOKEN_REVOKED, "access_token 已撤销");
        }
        return app;
    }

    private ApplicationEntity credentials(String appId, String appKey) {
        ApplicationEntity app = applications.getByAppKey(appKey);
        if (app == null || !String.valueOf(app.getId()).equals(appId)) {
            throw new WcsException(ErrorCode.OPEN_CREDENTIAL_INVALID, "应用调用凭证无效");
        }
        if (!Boolean.TRUE.equals(app.getEnabled()) || !Boolean.TRUE.equals(app.getIs_valid())) {
            throw new WcsException(ErrorCode.OPEN_APP_DISABLED, "应用已停用");
        }
        return app;
    }

    private static String hash(String value) {
        return CryptoSupport.sha256Hex((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean equal(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public record TokenRequest(String app_id, String app_key, String app_secret, String grant_type, String request_id) { }
    public record TokenData(String app_id, String app_key, String access_token, long expires_in, String issued_at, String expires_at) { }
    private record TokenRow(Long appId, Long tenantId, String credentialHash, long expiresAt) { }
}
