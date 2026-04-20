package com.cloudchunk.core.config;

import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 将 Caffeine 缓存统计绑定到 Micrometer，暴露给 /actuator/metrics。
 * <p>
 * 暴露指标包括：
 * - cache.gets{result=hit|miss}  命中/未命中次数
 * - cache.evictions              驱逐次数
 * - cache.size                   当前缓存条目数
 * - cache.eviction.weight        被驱逐的总权重
 */
@Configuration
@ConditionalOnClass(MeterRegistry.class)
public class MetricsConfig {

    public MetricsConfig(FileMetaService fileMetaService, MeterRegistry registry) {
        Cache<String, FileMeta> cache = fileMetaService.getLocalCache();
        if (cache != null) {
            CaffeineCacheMetrics.monitor(registry, cache, "fileMeta",
                    List.of(Tag.of("layer", "L1")));
        }
    }
}
