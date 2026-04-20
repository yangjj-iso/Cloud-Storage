package com.cloudchunk.infra.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis Lua 的滑动窗口限流。
 * 每次调用 tryAcquire 原子地对 key 计数 +1 并设置 TTL。
 */
@Component
public class RateLimiter {

    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local cur = redis.call('INCR', KEYS[1]) " +
                    "if cur == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
                    "return cur",
            Long.class);

    private final RedisService redisService;

    public RateLimiter(RedisService redisService) {
        this.redisService = redisService;
    }

    /**
     * @param key    限流 key
     * @param max    窗口内最大请求数
     * @param window 窗口时长
     * @return true 表示允许通过；false 表示超限
     */
    public boolean tryAcquire(String key, int max, Duration window) {
        Long cur = redisService.raw().execute(SCRIPT, List.of(key),
                String.valueOf(window.toSeconds()));
        return cur != null && cur <= max;
    }
}
