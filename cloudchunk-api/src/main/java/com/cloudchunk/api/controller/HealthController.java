package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.core.system.RuntimeInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final RuntimeInfoService runtimeInfoService;

    public HealthController(RuntimeInfoService runtimeInfoService) {
        this.runtimeInfoService = runtimeInfoService;
    }

    @GetMapping("/ping")
    public R<Map<String, Object>> ping() {
        return R.ok(runtimeInfoService.ping());
    }
}
