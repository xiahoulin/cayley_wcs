package com.cayleywcs.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RefreshTokenStore {
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final Map<Long, String> fallbackTokens = new ConcurrentHashMap<>();

    public RefreshTokenStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplateProvider = redisTemplateProvider;
    }

    public void store(Long userId, String refreshToken, int minutes) {
        fallbackTokens.put(userId, refreshToken);
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(userId), refreshToken, Duration.ofMinutes(minutes));
        } catch (RuntimeException ignored) {
            // 本地测试或未启动 Redis 时使用内存兜底。
        }
    }

    public boolean exists(Long userId, String refreshToken) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            try {
                String value = redisTemplate.opsForValue().get(key(userId));
                if (refreshToken.equals(value)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 本地测试或未启动 Redis 时使用内存兜底。
            }
        }
        return refreshToken.equals(fallbackTokens.get(userId));
    }

    private String key(Long userId) {
        return "cayleywcs:refresh-token:" + userId;
    }
}
