package com.cloudchunk.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.nio.charset.StandardCharsets;

/**
 * Redis Pub/Sub 缓存失效监听器：
 * 当任意实例发布 file_meta 缓存失效事件时，本实例清除对应 Caffeine 本地缓存。
 * 解决多实例部署下 Caffeine + Redis 两级缓存的一致性问题。
 */
public class CacheInvalidateListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidateListener.class);

    private final Cache<String, ?> localCache;

    public CacheInvalidateListener(Cache<String, ?> localCache) {
        this.localCache = localCache;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (localCache == null) return;
        String fileId = new String(message.getBody(), StandardCharsets.UTF_8);
        localCache.invalidate(fileId);
        if (log.isDebugEnabled()) {
            log.debug("cache invalidated via pub/sub: {}", fileId);
        }
    }
}
