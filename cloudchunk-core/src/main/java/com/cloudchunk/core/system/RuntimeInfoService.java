package com.cloudchunk.core.system;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class RuntimeInfoService {
    public Map<String, Object> ping() {
        return Map.of("status", "UP");
    }
}
