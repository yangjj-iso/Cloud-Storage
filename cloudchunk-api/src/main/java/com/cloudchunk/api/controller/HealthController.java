package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.storage.StorageStrategyFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final StorageStrategyFactory storageFactory;

    public HealthController(StorageStrategyFactory storageFactory) {
        this.storageFactory = storageFactory;
    }

    @GetMapping("/ping")
    public R<Map<String, Object>> ping() {
        return R.ok(Map.of(
                "status", "UP",
                "storage", storageFactory.current().type(),
                "bucket", storageFactory.defaultBucket()
        ));
    }
}
