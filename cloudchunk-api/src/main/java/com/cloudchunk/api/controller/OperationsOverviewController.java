package com.cloudchunk.api.controller;

import com.cloudchunk.api.monitoring.OperationsOverviewService;
import com.cloudchunk.common.model.R;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin only via existing /api/v1/admin/** role policy. Never changes alerts. */
@RestController
@RequestMapping("/api/v1/admin")
public class OperationsOverviewController {
    private final OperationsOverviewService operations;

    public OperationsOverviewController(OperationsOverviewService operations) {
        this.operations = operations;
    }

    @GetMapping("/operations")
    public ResponseEntity<R<OperationsOverviewService.Overview>> overview() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(R.ok(operations.snapshot()));
    }
}
