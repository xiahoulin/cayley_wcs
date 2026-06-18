package com.cayleywcs.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 轻量 HS256 JWT，移植自 CayleyWMS（保持 Bearer 令牌契约一致）。
 */
@Service
public class JwtService {
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte[] secret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtService(@Value("${cayleywcs.auth.jwt-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String generateToken(LoginUserRow user, int expireMinutes) {
        return generateToken(new JwtUser(
                user.getUserId(),
                user.getUserNum(),
                user.getUserName(),
                user.getUserRole(),
                user.getTenantId(),
                user.getUserroleId()
        ), expireMinutes);
    }

    public String generateToken(JwtUser user, int expireMinutes) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.userId());
        claims.put("user_num", user.userNum());
        claims.put("user_name", user.userName());
        claims.put("user_role", user.userRole());
        claims.put("tenant_id", user.tenantId());
        claims.put("userrole_id", user.userroleId());
        claims.put("iat", now);
        claims.put("exp", now + expireMinutes * 60L);
        return sign(claims);
    }

    public String generateRefreshToken() {
        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        return BASE64_URL_ENCODER.encodeToString(bytes);
    }

    public Optional<JwtUser> parse(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            String signingInput = parts[0] + "." + parts[1];
            String expectedSignature = BASE64_URL_ENCODER.encodeToString(hmac(signingInput));
            if (!constantTimeEquals(expectedSignature, parts[2])) {
                return Optional.empty();
            }
            Map<String, Object> claims = objectMapper.readValue(
                    BASE64_URL_DECODER.decode(parts[1]),
                    new TypeReference<>() {
                    });
            Number exp = (Number) claims.get("exp");
            if (exp == null || exp.longValue() < Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(new JwtUser(
                    asLong(claims.get("sub")),
                    (String) claims.get("user_num"),
                    (String) claims.get("user_name"),
                    (String) claims.get("user_role"),
                    asLong(claims.get("tenant_id")),
                    asLong(claims.get("userrole_id"))
            ));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String sign(Map<String, Object> claims) {
        try {
            String header = BASE64_URL_ENCODER.encodeToString(
                    objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            String payload = BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
            String signingInput = header + "." + payload;
            return signingInput + "." + BASE64_URL_ENCODER.encodeToString(hmac(signingInput));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create JWT", ex);
        }
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign JWT", ex);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        if (leftBytes.length != rightBytes.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < leftBytes.length; i++) {
            result |= leftBytes[i] ^ rightBytes[i];
        }
        return result == 0;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }
}
