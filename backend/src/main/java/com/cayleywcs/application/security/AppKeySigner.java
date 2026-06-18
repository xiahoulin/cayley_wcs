package com.cayleywcs.application.security;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 开放接口 AppKey 签名（HMAC-SHA256）。
 * 规范串：appKey + "\n" + timestamp + "\n" + nonce + "\n" + method + "\n" + path。
 */
@Component
public class AppKeySigner {

    public String sign(String appSecret, String appKey, String timestamp, String nonce, String method, String path) {
        String canonical = String.join("\n", appKey, timestamp, nonce, method, path);
        return hmacHex(appSecret, canonical);
    }

    public boolean verify(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length; i++) {
            r |= a[i] ^ b[i];
        }
        return r == 0;
    }

    private static String hmacHex(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte x : bytes) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC failed", ex);
        }
    }
}
