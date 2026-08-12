package com.cloudchunk.infra.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按负载解析 {@link StringRedisTemplate}。
 * <p>
 * 没有为某个负载配置独立实例时返回主模板，因此默认部署下所有负载仍然共用一个 Redis，
 * 行为与拆分前逐字节一致——拆分是可选的运维手段，不是必须承担的复杂度。
 */
public class RedisWorkloadTemplates implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisWorkloadTemplates.class);

    private final Map<RedisWorkload, StringRedisTemplate> templates = new EnumMap<>(RedisWorkload.class);
    private final List<LettuceConnectionFactory> ownedFactories = new ArrayList<>();

    public RedisWorkloadTemplates(StringRedisTemplate primary, RedisWorkloadProperties properties) {
        for (RedisWorkload workload : RedisWorkload.values()) {
            RedisWorkloadProperties.Endpoint endpoint = properties.forWorkload(workload);
            if (endpoint == null || !endpoint.isConfigured()) {
                templates.put(workload, primary);
                continue;
            }
            templates.put(workload, dedicatedTemplate(workload, endpoint));
        }
    }

    private StringRedisTemplate dedicatedTemplate(RedisWorkload workload,
                                                  RedisWorkloadProperties.Endpoint endpoint) {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(endpoint.getHost(), endpoint.getPort());
        configuration.setDatabase(endpoint.getDatabase());
        if (endpoint.getPassword() != null && !endpoint.getPassword().isBlank()) {
            configuration.setPassword(endpoint.getPassword());
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        ownedFactories.add(factory);

        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        log.info("redis workload {} bound to dedicated instance {}:{} db={}",
                workload, endpoint.getHost(), endpoint.getPort(), endpoint.getDatabase());
        return template;
    }

    public StringRedisTemplate forWorkload(RedisWorkload workload) {
        return templates.get(workload);
    }

    /** 只关闭本类创建的连接工厂；主模板的工厂由 Spring 自己管理。 */
    @Override
    public void close() {
        for (LettuceConnectionFactory factory : ownedFactories) {
            try {
                factory.destroy();
            } catch (Exception e) {
                log.warn("closing dedicated redis connection factory failed", e);
            }
        }
        ownedFactories.clear();
    }
}
