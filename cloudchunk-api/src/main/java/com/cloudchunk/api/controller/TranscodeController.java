package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.transcode.dto.TranscodeStatusView;
import com.cloudchunk.core.transcode.service.TranscodeApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/transcode")
public class TranscodeController {

    private final TranscodeApplicationService transcodeService;

    public TranscodeController(TranscodeApplicationService transcodeService) {
        this.transcodeService = transcodeService;
    }

    @GetMapping("/{fileId}")
    public R<TranscodeStatusView> status(@PathVariable String fileId) {
        long userId = UserContext.requireUserId();
        return R.ok(transcodeService.status(fileId, userId));
    }

    @PostMapping("/{fileId}/retry")
    public R<Void> retry(@PathVariable String fileId) {
        long userId = UserContext.requireUserId();
        transcodeService.requestRetry(fileId, userId);
        return R.ok();
    }
}
