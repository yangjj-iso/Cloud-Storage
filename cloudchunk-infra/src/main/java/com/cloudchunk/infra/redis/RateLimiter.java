package com.cloudchunk.infra.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Redis Lua 的令牌桶限流器。
 * <p>
 * 每次调用 {@link #tryAcquire} 原子地执行：
 * 1. 计算距上次补充过去的时间，按 rate 补充令牌（上限 capacity）
 * 2. 若桶中 ≥1 个令牌则扣减 1 并放行，否则拒绝
 * 3. 将最新桶状态写回 Hash 并设 EXPIRE
 * <p>
 * 全部逻辑在 Redis 单线程中完成，无竞态、无额外 RTT。
 */
@Component
public class RateLimiter {

    /**
     * KEYS[1] = bucket key
     * ARGV[1] = rate (tokens/s)
     * ARGV[2] = capacity (burst)
     * ARGV[3] = now (microseconds, System.nanoTime()/1000 或 currentTimeMillis*1000)
     * returns  1 = allowed, 0 = rejected
     */
    private static final RedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>(
            "local key = KEYS[1] " +
            "local rate = tonumber(ARGV[1]) " +
            "local cap  = tonumber(ARGV[2]) " +
            "local now  = tonumber(ARGV[3]) " +
            "local d = redis.call('HMGET', key, 'tk', 'ts') " +
            "local tokens = tonumber(d[1]) " +
            "local last   = tonumber(d[2]) " +
            "if tokens == nil then tokens = cap; last = now end " +
            "local elapsed = math.max(0, now - last) " +
            "tokens = math.min(cap, tokens + elapsed * rate / 1000000) " +
            "local allowed = 0 " +
            "if tokens >= 1 then tokens = tokens - 1; allowed = 1 end " +
            "redis.call('HMSET', key, 'tk', tostring(tokens), 'ts', tostring(now)) " +
            "redis.call('EXPIRE', key, math.ceil(cap / rate) + 1) " +
            "return allowed",
            Long.class);

    private final RedisService redisService;

    public RateLimiter(RedisService redisService) {
        this.redisService = redisService;
    }

    /**
     * 尝试获取一个令牌。
     *
     * @param key      Redis key（建议携带 userId / IP 维度）
     * @param rate     令牌补充速率 tokens/s
     * @param capacity 桶容量（允许的瞬时突发）
     * @return true=放行  false=被限流
     */
    public boolean tryAcquire(String key, int rate, int capacity) {
        long nowMicros = System.currentTimeMillis() * 1000L;
        Long ok = redisService.raw().execute(TOKEN_BUCKET, List.of(key),
                String.valueOf(rate), String.valueOf(capacity), String.valueOf(nowMicros));
        return ok != null && ok == 1L;
    }
}
