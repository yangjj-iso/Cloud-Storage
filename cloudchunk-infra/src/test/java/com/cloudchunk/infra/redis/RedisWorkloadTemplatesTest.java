package com.cloudchunk.infra.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisWorkloadTemplatesTest {

    private static RedisWorkloadProperties.Endpoint endpoint(String host) {
        RedisWorkloadProperties.Endpoint e = new RedisWorkloadProperties.Endpoint();
        e.setHost(host);
        return e;
    }

    /** 不配置任何独立实例时，拆分必须完全无感：每个负载拿到的都是主模板。 */
    @Test
    void everyWorkloadFallsBackToThePrimaryWhenNothingIsConfigured() {
        StringRedisTemplate primary = mock(StringRedisTemplate.class);

        try (RedisWorkloadTemplates templates =
                     new RedisWorkloadTemplates(primary, new RedisWorkloadProperties())) {
            for (RedisWorkload workload : RedisWorkload.values()) {
                assertThat(templates.forWorkload(workload)).isSameAs(primary);
            }
        }
    }

    /** 只配了 port 而没有 host 不算指定实例，避免半截配置把负载指到 localhost。 */
    @Test
    void anEndpointWithoutAHostIsNotConsideredConfigured() {
        RedisWorkloadProperties.Endpoint portOnly = new RedisWorkloadProperties.Endpoint();
        portOnly.setPort(6399);
        assertThat(portOnly.isConfigured()).isFalse();

        RedisWorkloadProperties properties = new RedisWorkloadProperties();
        properties.setCache(portOnly);
        StringRedisTemplate primary = mock(StringRedisTemplate.class);

        try (RedisWorkloadTemplates templates = new RedisWorkloadTemplates(primary, properties)) {
            assertThat(templates.forWorkload(RedisWorkload.CACHE)).isSameAs(primary);
        }
    }

    /** 会话负载不可配置：主实例就是会话存储，剥离的只能是可再生的那几类。 */
    @Test
    void sessionAlwaysResolvesToThePrimary() {
        RedisWorkloadProperties properties = new RedisWorkloadProperties();
        properties.setProgress(endpoint("progress-redis"));
        properties.setCache(endpoint("cache-redis"));

        assertThat(properties.forWorkload(RedisWorkload.SESSION)).isNull();
    }

    @Test
    void configuredWorkloadsResolveAwayFromThePrimary() {
        RedisWorkloadProperties properties = new RedisWorkloadProperties();
        properties.setProgress(endpoint("progress-redis"));
        StringRedisTemplate primary = mock(StringRedisTemplate.class);

        try (RedisWorkloadTemplates templates = new RedisWorkloadTemplates(primary, properties)) {
            assertThat(templates.forWorkload(RedisWorkload.PROGRESS)).isNotSameAs(primary);
            assertThat(templates.forWorkload(RedisWorkload.SESSION)).isSameAs(primary);
            assertThat(templates.forWorkload(RedisWorkload.RATE_LIMIT)).isSameAs(primary);
        }
    }
}
