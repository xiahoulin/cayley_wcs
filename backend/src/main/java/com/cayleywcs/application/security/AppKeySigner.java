package com.cayleywcs.application.security;

import com.cayleywcs.common.support.CryptoSupport;
import org.springframework.stereotype.Component;

/**
 * 开放接口 AppKey 签名（HMAC-SHA256）。
 * v1 规范串：appKey\ntimestamp\nnonce\nmethod\npath。
 * v2 规范串（M13，闭合 body 篡改）：v1 之后追加 \nsha256Hex(body)——坐标/任务类型等 body 内容也纳入签名。
 */
@Component
public class AppKeySigner {

    public String sign(String appSecret, String appKey, String timestamp, String nonce, String method, String path) {
        String canonical = String.join("\n", appKey, timestamp, nonce, method, path);
        return CryptoSupport.hmacSha256Hex(appSecret, canonical);
    }

    public String signV2(String appSecret, String appKey, String timestamp, String nonce,
                         String method, String path, String bodySha256Hex) {
        String canonical = String.join("\n", appKey, timestamp, nonce, method, path, bodySha256Hex);
        return CryptoSupport.hmacSha256Hex(appSecret, canonical);
    }

    public boolean verify(String expected, String actual) {
        return CryptoSupport.constantTimeEquals(expected, actual);
    }
}
