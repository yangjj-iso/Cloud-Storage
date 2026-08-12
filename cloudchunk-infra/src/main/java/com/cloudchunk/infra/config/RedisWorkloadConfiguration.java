package com.cloudchunk.infra.config;

import com.cloudchunk.infra.redis.RedisService;
import com.cloudchunk.infra.redis.RedisWorkload;
import com.cloudchunk.infra.redis.RedisWorkloadProperties;
import com.cloudchunk.infra.redis.RedisWorkloadTemplates;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 按负载暴露 {@link RedisService}。
 * <p>
 * 未配置独立实例时每个负载拿到的都是主模板，所以默认部署与拆分前没有任何差别。
 * <p>
 * Pub/Sub 不走这里：channel 不是存储，没有驱逐问题，而发布方与订阅方一旦解析到不同实例，
 * 消息就会静默消失。让它始终留在主连接上可以从根上排除这类不一致。
 */
@Configuration
@EnableConfigurationProperties(RedisWorkloadProperties.class)
public class RedisWorkloadConfiguration {

    public static final String PROGRESS = "progressRedisService";
    public static final String RATE_LIMIT = "rateLimitRedisService";
    public static final String CACHE = "cacheRedisService";

    @Bean(destroyMethod = "close")
    public RedisWorkloadTemplates redisWorkloadTemplates(StringRedisTemplate primary,
                                                         RedisWorkloadProperties properties) {
        return new RedisWorkloadTemplates(primary, properties);
    }

    @Bean
    @Primary
    public RedisService redisService(RedisWorkloadTemplates templates) {
        return new RedisService(templates.forWorkload(RedisWorkload.SESSION));
    }

    @Bean(PROGRESS)
    public RedisService progressRedisService(RedisWorkloadTemplates templates) {
        return new RedisService(templates.forWorkload(RedisWorkload.PROGRESS));
    }

    @Bean(RATE_LIMIT)
    public RedisService rateLimitRedisService(RedisWorkloadTemplates templates) {
        return new RedisService(templates.forWorkload(RedisWorkload.RATE_LIMIT));
    }

    @Bean(CACHE)
    public RedisService cacheRedisService(RedisWorkloadTemplates templates) {
        return new RedisService(templates.forWorkload(RedisWorkload.CACHE));
    }
}
