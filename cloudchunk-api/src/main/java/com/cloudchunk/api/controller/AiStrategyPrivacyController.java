package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.knowledge.AiStrategyPrivacyGovernanceService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Authenticated, owner-scoped and content-free AI consent history. */
@RestController
@RequestMapping("/api/v1/knowledge/assistant/strategy")
public class AiStrategyPrivacyController {
    private final AiStrategyPrivacyGovernanceService governance;
    public AiStrategyPrivacyController(AiStrategyPrivacyGovernanceService governance) {
        this.governance=governance;
    }
    @GetMapping("/consent-history")
    public ResponseEntity<R<List<AiStrategyPrivacyGovernanceService.ConsentReceipt>>> history() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(R.ok(governance.history(UserContext.requireUserId())));
    }
}
