package com.cayleywcs.application.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Also cleans legacy nonce keys when the installation uses application tokens instead of signatures. */
@Component
public class AppKeyReplayCache {
    private static final Logger log=LoggerFactory.getLogger(AppKeyReplayCache.class);
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final long maxSkewSeconds;
    private final Map<String,Long> fallback=new ConcurrentHashMap<>();
    private final Map<String,Long> pendingEvictions=new ConcurrentHashMap<>();

    public AppKeyReplayCache(ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${cayleywcs.openapi.signature-max-skew-seconds:300}") long maxSkewSeconds) {
        this.redisProvider=redisProvider;this.maxSkewSeconds=maxSkewSeconds;
    }

    public boolean isReplay(String appKey,String nonce) {
        String key=prefix(appKey)+nonce;
        var redis=redisProvider.getIfAvailable();
        if(redis!=null) {
            try{return !Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key,"1",Duration.ofSeconds(maxSkewSeconds+5)));}
            catch(RuntimeException ignored){ /* Continue with the process-local replay guard. */ }
        }
        long now=Instant.now().getEpochSecond();
        fallback.entrySet().removeIf(e->now-e.getValue()>maxSkewSeconds+5);
        return fallback.putIfAbsent(key,now)!=null;
    }

    public void evictApplication(String appKey) {
        if(appKey==null || appKey.isBlank())return;
        fallback.keySet().removeIf(key->key.startsWith(prefix(appKey)));
        pendingEvictions.putIfAbsent(appKey,Instant.now().getEpochSecond()+maxSkewSeconds+10);
        evictRedis(appKey);
    }

    private void evictRedis(String appKey) {
        try {
            var redis=redisProvider.getIfAvailable();
            if(redis!=null) {
                try(var cursor=redis.scan(ScanOptions.scanOptions().match("cayleywcs:appkey:nonce:*").count(200).build())) {
                    while(cursor.hasNext()){String key=cursor.next();if(key.startsWith(prefix(appKey)))redis.delete(key);}
                }
            }
            pendingEvictions.remove(appKey);
        } catch(RuntimeException ex) {
            log.warn("已删除应用的重放缓存清理暂不可用，将重试；应用身份已失效");
        }
    }

    @Scheduled(fixedDelayString="${cayleywcs.lifecycle.cache-retry-ms:5000}")
    public void retryEvictions() {
        long now=Instant.now().getEpochSecond();
        pendingEvictions.forEach((key,until)->{
            if(now>=until)pendingEvictions.remove(key); // Nonce keys have a bounded TTL.
            else evictRedis(key);
        });
    }
    private static String prefix(String appKey){return "cayleywcs:appkey:nonce:"+appKey+":";}
}
