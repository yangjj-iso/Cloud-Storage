package com.cloudchunk.storage;

import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.exception.StorageException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class StorageStrategyFactory {

    private final Map<String, StorageStrategy> strategies;
    private final StorageProperties properties;

    public StorageStrategyFactory(List<StorageStrategy> all, StorageProperties properties,
                                  CircuitBreakerRegistry circuitBreakerRegistry) {
        // 远程对象存储（minio）用熔断器包裹，故障时快速失败防雪崩；本地磁盘策略无需熔断。
        CircuitBreaker storageCb = circuitBreakerRegistry.circuitBreaker("storage");
        Map<String, StorageStrategy> m = new HashMap<>();
        for (StorageStrategy s : all) {
            StorageStrategy effective = "minio".equals(s.type())
                    ? new ResilientStorageStrategy(s, storageCb) : s;
            m.put(s.type(), effective);
        }
        this.strategies = Map.copyOf(m);
        this.properties = properties;
    }

    public StorageStrategy current() {
        return byType(properties.getType());
    }

    public StorageStrategy byType(String type) {
        StorageStrategy s = strategies.get(type);
        if (s == null) {
            throw new StorageException(ErrorCode.STORAGE_UNAVAILABLE, "unknown storage type: " + type);
        }
        return s;
    }

    public String defaultBucket() {
        return properties.getDefaultBucket();
    }
}
