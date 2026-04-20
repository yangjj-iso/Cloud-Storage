package com.cloudchunk.infra.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * StringRedisTemplate 的薄封装，统一 TTL 语义并屏蔽常用操作。
 */
@Component
public class RedisService {

    private final StringRedisTemplate redis;

    public RedisService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public StringRedisTemplate raw() { return redis; }

    /* ---------- String ---------- */

    public void set(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    public Boolean setIfAbsent(String key, String value, Duration ttl) {
        return redis.opsForValue().setIfAbsent(key, value, ttl);
    }

    public Boolean delete(String key) {
        return redis.delete(key);
    }

    public Long delete(Collection<String> keys) {
        return redis.delete(keys);
    }

    public Boolean hasKey(String key) {
        return redis.hasKey(key);
    }

    public Boolean expire(String key, Duration ttl) {
        return redis.expire(key, ttl);
    }

    /* ---------- Hash ---------- */

    public void hSet(String key, String field, String value) {
        redis.opsForHash().put(key, field, value);
    }

    public void hSetAll(String key, Map<String, String> values, Duration ttl) {
        if (values == null || values.isEmpty()) return;
        redis.opsForHash().putAll(key, values);
        if (ttl != null) {
            redis.expire(key, ttl);
        }
    }

    public String hGet(String key, String field) {
        Object v = redis.opsForHash().get(key, field);
        return v == null ? null : v.toString();
    }

    public Map<Object, Object> hGetAll(String key) {
        return redis.opsForHash().entries(key);
    }

    public Long hDel(String key, String... fields) {
        return redis.opsForHash().delete(key, (Object[]) fields);
    }

    public Set<Object> hKeys(String key) {
        return redis.opsForHash().keys(key);
    }

    public Long hSize(String key) {
        return redis.opsForHash().size(key);
    }
}
