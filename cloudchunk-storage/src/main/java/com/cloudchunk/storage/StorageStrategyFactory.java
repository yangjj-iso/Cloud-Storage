package com.cloudchunk.storage;

import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.exception.StorageException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class StorageStrategyFactory {

    private final Map<String, StorageStrategy> strategies;
    private final StorageProperties properties;

    public StorageStrategyFactory(List<StorageStrategy> all, StorageProperties properties) {
        Map<String, StorageStrategy> m = new HashMap<>();
        for (StorageStrategy s : all) {
            m.put(s.type(), s);
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
