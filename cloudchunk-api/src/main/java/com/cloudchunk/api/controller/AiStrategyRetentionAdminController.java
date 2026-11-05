package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.knowledge.AiStrategyPrivacyGovernanceService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Existing /api/v1/admin/** ADMIN-only role gate applies to this controller. */
@RestController
@RequestMapping("/api/v1/admin/strategy-retention")
public class AiStrategyRetentionAdminController {
    private final AiStrategyPrivacyGovernanceService governance;
    public AiStrategyRetentionAdminController(AiStrategyPrivacyGovernanceService governance) {
        this.governance=governance;
    }
    @GetMapping("/preview")
    public ResponseEntity<R<AiStrategyPrivacyGovernanceService.RetentionPreview>> preview() {
        UserContext.requireUserId();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(R.ok(governance.preview()));
    }
    @PostMapping("/purge")
    public ResponseEntity<R<AiStrategyPrivacyGovernanceService.CleanupReceipt>> purge(
            @RequestBody AiStrategyPrivacyGovernanceService.CleanupRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(R.ok(governance.purge(UserContext.requireUserId(),req)));
    }
}
