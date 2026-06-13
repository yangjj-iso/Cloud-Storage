package com.cloudchunk.core.config;

import com.cloudchunk.common.concurrent.BoundedVirtualThreadExecutor;
import com.cloudchunk.common.constant.RedisKeys;
import com.cloudchunk.core.CloudchunkProperties;
import com.cloudchunk.core.cache.CacheInvalidateListener;
import com.cloudchunk.core.file.service.FileMetaService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@EnableConfigurationProperties(CloudchunkProperties.class)
public class CoreConfig {

    @Bean(destroyMethod = "close")
    public BoundedVirtualThreadExecutor ioExecutor(CloudchunkProperties props) {
        CloudchunkProperties.Executor e = props.getExecutor();
        return new BoundedVirtualThreadExecutor("io", e.getIoConcurrency(), e.getAcquireTimeoutMs());
    }

    @Bean(destroyMethod = "close")
    public BoundedVirtualThreadExecutor cleanupExecutor(CloudchunkProperties props) {
        CloudchunkProperties.Executor e = props.getExecutor();
        return new BoundedVirtualThreadExecutor("cleanup", e.getCleanupConcurrency(), e.getAcquireTimeoutMs());
    }

    /**
     * Redis Pub/Sub 监听容器：订阅 file_meta 缓存失效 channel，
     * 当其他实例写入/更新 file_meta 时，本实例自动清除对应的 Caffeine 本地缓存。
     */
    @Bean
    public RedisMessageListenerContainer cacheInvalidateListenerContainer(
            RedisConnectionFactory connectionFactory,
            @Lazy FileMetaService fileMetaService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        if (fileMetaService.getLocalCache() != null) {
            CacheInvalidateListener listener = new CacheInvalidateListener(fileMetaService.getLocalCache());
            container.addMessageListener(listener,
                    new ChannelTopic(RedisKeys.CHANNEL_CACHE_INVALIDATE));
        }
        return container;
    }
}
