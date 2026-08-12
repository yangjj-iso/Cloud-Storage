package com.cloudchunk.storage;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 为存储模块提供独立可用的熔断器注册表。
 *
 * <p>项目只依赖 resilience4j-circuitbreaker 核心包，没有引入 Spring Boot Starter，
 * 因此不能假设 Starter 会自动创建 {@link CircuitBreakerRegistry}。如果部署环境另外
 * 提供了注册表，此配置会通过 {@link ConditionalOnMissingBean} 自动让位。</p>
 */
@Configuration(proxyBeanMethods = false)
public class StorageCircuitBreakerConfiguration {

    @Bean
    @ConditionalOnMissingBean(CircuitBreakerRegistry.class)
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig storage = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return CircuitBreakerRegistry.of(storage);
    }
}

