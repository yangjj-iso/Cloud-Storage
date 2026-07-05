package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.preview.dto.PreviewResult;
import com.cloudchunk.core.preview.service.PreviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件预览：GET /api/v1/preview/{id}（id 为网盘节点 id）。
 */
@RestController
@RequestMapping("/api/v1/preview")
public class PreviewController {

    private final PreviewService previewService;

    public PreviewController(PreviewService previewService) {
        this.previewService = previewService;
    }

    @GetMapping("/{id}")
    public R<PreviewResult> preview(@PathVariable long id) {
        long userId = UserContext.requireUserId();
        return R.ok(previewService.preview(userId, id));
    }
}
