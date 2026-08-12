package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.quota.dto.QuotaView;
import com.cloudchunk.core.quota.service.QuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quota")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping("/me")
    public R<QuotaView> me() {
        long userId = UserContext.requireUserId();
        return R.ok(QuotaView.from(quotaService.getOrDefault(userId)));
    }
}
