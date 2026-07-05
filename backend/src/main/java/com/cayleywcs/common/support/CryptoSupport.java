package com.cayleywcs.common.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 签名/摘要工具：HMAC-SHA256、SHA-256（十六进制小写）。AppKey 签名与 Webhook 回调签名共用。 */
public final class CryptoSupport {

    private CryptoSupport() {
    }

    public static String hmacSha256Hex(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC failed", ex);
        }
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    /** 对原始字节求 SHA-256（body 纳签用原始字节，避免 bytes→String→bytes 往返在非 UTF-8 上失配）。 */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(bytes == null ? new byte[0] : bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 failed", ex);
        }
    }

    /** 定时比较，防时序侧信道。 */
    public static boolean constantTimeEquals(String expected, String actual) {
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

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
