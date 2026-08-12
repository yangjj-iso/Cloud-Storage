package com.cloudchunk.infra.config;

import com.cloudchunk.infra.redis.RedisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 装配层面的验证。
 * <p>
 * 拆分负载之后容器里存在四个 {@link RedisService}，注入歧义或者限定符写错只会在应用真正启动时
 * 才暴露，而任何切片测试都覆盖不到这一层。
 */
class RedisWorkloadConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(PrimaryTemplateConfig.class, RedisWorkloadConfiguration.class);

    @Test
    void exposesOneServicePerWorkloadWithoutAmbiguity() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(RedisService.class)).hasSize(4);
            assertThat(context).hasBean(RedisWorkloadConfiguration.PROGRESS);
            assertThat(context).hasBean(RedisWorkloadConfiguration.RATE_LIMIT);
            assertThat(context).hasBean(RedisWorkloadConfiguration.CACHE);
        });
    }

    /** 未加限定符的注入点必须落到主实例，否则会话数据会被悄悄写到别的 Redis 上。 */
    @Test
    void anUnqualifiedInjectionPointResolvesToTheSessionService() {
        runner.withUserConfiguration(Consumers.class).run(context -> {
            assertThat(context).hasNotFailed();
            Consumers consumers = context.getBean(Consumers.class);
            assertThat(consumers.unqualified)
                    .isSameAs(context.getBean("redisService", RedisService.class));
        });
    }

    @Test
    void qualifiedInjectionPointsResolveToTheirOwnWorkload() {
        runner.withUserConfiguration(Consumers.class).run(context -> {
            assertThat(context).hasNotFailed();
            Consumers consumers = context.getBean(Consumers.class);
            assertThat(consumers.progress)
                    .isSameAs(context.getBean(RedisWorkloadConfiguration.PROGRESS, RedisService.class));
            assertThat(consumers.cache)
                    .isSameAs(context.getBean(RedisWorkloadConfiguration.CACHE, RedisService.class));
        });
    }

    @Configuration
    static class PrimaryTemplateConfig {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }

    @Configuration
    static class Consumers {
        final RedisService unqualified;
        final RedisService progress;
        final RedisService cache;

        Consumers(RedisService unqualified,
                  @Qualifier(RedisWorkloadConfiguration.PROGRESS) RedisService progress,
                  @Qualifier(RedisWorkloadConfiguration.CACHE) RedisService cache) {
            this.unqualified = unqualified;
            this.progress = progress;
            this.cache = cache;
        }
    }
}
