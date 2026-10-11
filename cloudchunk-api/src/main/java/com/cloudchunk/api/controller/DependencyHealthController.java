package com.cloudchunk.api.controller;

import com.cloudchunk.api.monitoring.DependencyHealthService;
import com.cloudchunk.common.model.R;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Covered by the existing /api/v1/admin/** role check. No mutation endpoints. */
@RestController
@RequestMapping("/api/v1/admin")
public class DependencyHealthController {
    private final DependencyHealthService service;

    public DependencyHealthController(DependencyHealthService service) {
        this.service = service;
    }

    @GetMapping("/health-summary")
    public ResponseEntity<R<DependencyHealthService.Snapshot>> summary() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(R.ok(service.snapshot()));
    }
}
