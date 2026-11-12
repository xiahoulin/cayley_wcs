package com.cayleywcs.application.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.support.CryptoSupport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M13 签名 v2（body 纳签）：v1 兼容通过、v2 有效通过、v2 篡改 body 拒绝、min_sign_version=2 拒 v1、v2 body 下游可复读。
 */
class AppKeySignV2Test {
    private static final String APP_KEY = "ak_test";
    private static final String SECRET = "secret_test";
    private static final String URI = "/open/task/dispatch";

    private final AppKeySigner signer = new AppKeySigner();

    private AppKeyAuthFilter filter(long minSignVersion) {
        ApplicationService appService = mock(ApplicationService.class);
        ApplicationEntity app = new ApplicationEntity();
        app.setId(1L);
        app.setApp_code("APP");
        app.setApp_key(APP_KEY);
        app.setApp_secret(SECRET);
        app.setTenant_id(1L);
        app.setEnabled(true);
        app.setMin_sign_version(minSignVersion);
        when(appService.getByAppKey(APP_KEY)).thenReturn(app);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
        when(redis.getIfAvailable()).thenReturn(null); // 走内存 nonce
        return new AppKeyAuthFilter(appService, signer, redis, true, 300);
    }

    private MockHttpServletRequest request(String body, String sign, String signVersion, String nonce) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", URI);
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        req.addHeader("X-App-Key", APP_KEY);
        req.addHeader("X-Timestamp", String.valueOf(java.time.Instant.now().getEpochSecond()));
        req.addHeader("X-Nonce", nonce);
        req.addHeader("X-Sign", sign);
        if (signVersion != null) {
            req.addHeader("X-Sign-Version", signVersion);
        }
        return req;
    }

    private static String ts(MockHttpServletRequest req) {
        return req.getHeader("X-Timestamp");
    }

    @Test
    void v1CompatiblePasses() throws Exception {
        AppKeyAuthFilter filter = filter(1);
        String body = "{\"appId\":5}";
        // 先建请求拿到 timestamp，再据其算签名
        MockHttpServletRequest req = request(body, "x", "1", "n-v1");
        String sign = signer.sign(SECRET, APP_KEY, ts(req), "n-v1", "POST", URI);
        req.removeHeader("X-Sign");
        req.addHeader("X-Sign", sign);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        assertThat(chain.getRequest()).as("v1 有效签名放行").isNotNull();
    }

    @Test
    void v2ValidPassesAndBodyReadableDownstream() throws Exception {
        AppKeyAuthFilter filter = filter(2); // 强制 v2
        String body = "{\"appId\":5,\"takeRow\":1}";
        MockHttpServletRequest req = request(body, "x", "2", "n-v2");
        String bodyHash = CryptoSupport.sha256Hex(body);
        String sign = signer.signV2(SECRET, APP_KEY, ts(req), "n-v2", "POST", URI, bodyHash);
        req.removeHeader("X-Sign");
        req.addHeader("X-Sign", sign);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        assertThat(chain.getRequest()).as("v2 有效签名放行").isNotNull();
        // 下游可复读 body（CachedBody 包装）
        String downstreamBody = new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(downstreamBody).isEqualTo(body);
        assertThat(chain.getRequest().getAttribute(AppKeyAuthFilter.ATTR_APP_ID)).isEqualTo(1L);
    }

    @Test
    void v2TamperedBodyRejected() throws Exception {
        AppKeyAuthFilter filter = filter(1);
        String signedBody = "{\"appId\":5,\"takeRow\":1}";
        String sign = signer.signV2(SECRET, APP_KEY,
                String.valueOf(java.time.Instant.now().getEpochSecond()), "n-tamper", "POST", URI,
                CryptoSupport.sha256Hex(signedBody));
        // 用篡改后的 body 发出（坐标被改），签名却是原 body 的 → 拒绝
        String tamperedBody = "{\"appId\":5,\"takeRow\":9}";
        MockHttpServletRequest req = request(tamperedBody, sign, "2", "n-tamper");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        assertThat(chain.getRequest()).as("篡改 body 应被拒").isNull();
        assertThat(resp.getContentAsString()).contains("isSuccess\":false");
    }

    @Test
    void minSignVersion2RejectsV1() throws Exception {
        AppKeyAuthFilter filter = filter(2); // 要求 >= v2
        String body = "{\"appId\":5}";
        MockHttpServletRequest req = request(body, "x", "1", "n-min"); // 用 v1
        String sign = signer.sign(SECRET, APP_KEY, ts(req), "n-min", "POST", URI);
        req.removeHeader("X-Sign");
        req.addHeader("X-Sign", sign);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        assertThat(chain.getRequest()).as("min_sign_version=2 拒绝 v1").isNull();
        assertThat(resp.getContentAsString()).contains("版本过低");
    }

    @Test
    void signerV2AppendsBodyHashToCanonical() {
        String v1 = signer.sign(SECRET, APP_KEY, "1000", "n", "POST", URI);
        String v2 = signer.signV2(SECRET, APP_KEY, "1000", "n", "POST", URI, CryptoSupport.sha256Hex("{}"));
        assertThat(v1).isNotEqualTo(v2); // body 纳入使签名不同
        assertThat(signer.verify(v2, v2)).isTrue();
    }

    @Test
    void applicationEvictionIsExactAndPreservesOtherReplayProtection() throws Exception {
        var filter=filter(1);
        assertThat(sendNonce(filter,"eviction").getRequest()).isNotNull();
        filter.evictApplication(APP_KEY+"_other");
        assertThat(sendNonce(filter,"eviction").getRequest()).isNull();
        filter.evictApplication(APP_KEY);
        assertThat(sendNonce(filter,"eviction").getRequest()).isNotNull();
    }

    private MockFilterChain sendNonce(AppKeyAuthFilter filter,String nonce) throws Exception {
        var request=request("{}","x","1",nonce);
        request.removeHeader("X-Sign");
        request.addHeader("X-Sign",signer.sign(SECRET,APP_KEY,ts(request),nonce,"POST",URI));
        var chain=new MockFilterChain();filter.doFilter(request,new MockHttpServletResponse(),chain);return chain;
    }

    @Test
    void redisFailureDoesNotUndoCommittedDeletionAndIsRetried() {
        @SuppressWarnings("unchecked") ObjectProvider<StringRedisTemplate> provider=mock(ObjectProvider.class);
        var redis=mock(StringRedisTemplate.class);when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.scan(org.mockito.ArgumentMatchers.any())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"));
        var filter=new AppKeyAuthFilter(mock(ApplicationService.class),signer,provider,true,300);
        org.assertj.core.api.Assertions.assertThatCode(()->filter.evictApplication(APP_KEY)).doesNotThrowAnyException();
        filter.retryEvictions();
        org.mockito.Mockito.verify(redis,org.mockito.Mockito.times(2)).scan(org.mockito.ArgumentMatchers.any());
    }
}
