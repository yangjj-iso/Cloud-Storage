package com.cloudchunk.infra.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Redis 分布式锁：SET NX EX + Lua 原子释放。
 * 持有 Token 的持有者才能释放锁，防止误删其他 client 的锁。
 */
@Component
public class RedisLock {

    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('DEL', KEYS[1]) " +
                    "else return 0 end",
            Long.class);

    private final RedisService redisService;

    public RedisLock(RedisService redisService) {
        this.redisService = redisService;
    }

    /** 尝试获取锁 */
    public boolean tryLock(String key, String token, Duration ttl) {
        Boolean ok = redisService.setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(ok);
    }

    /** 释放锁（Lua 原子判断 token） */
    public boolean unlock(String key, String token) {
        Long v = redisService.raw().execute(UNLOCK_SCRIPT, List.of(key), token);
        return v != null && v > 0;
    }

    /** 带锁执行；获取失败返回 null，由调用方决定异常处理 */
    public <T> T executeIfLocked(String key, String token, Duration ttl, Supplier<T> action) {
        if (!tryLock(key, token, ttl)) {
            return null;
        }
        try {
            return action.get();
        } finally {
            unlock(key, token);
        }
    }
}
