package com.cloudchunk.api.controller;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.CloudchunkProperties;
import com.cloudchunk.core.upload.dto.ChunkUploadResponse;
import com.cloudchunk.core.upload.dto.InitUploadRequest;
import com.cloudchunk.core.upload.dto.InitUploadResponse;
import com.cloudchunk.core.upload.dto.MergeResult;
import com.cloudchunk.core.upload.dto.UploadProgress;
import com.cloudchunk.core.upload.service.UploadService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/upload")
public class UploadController {

    private final UploadService uploadService;
    private final CloudchunkProperties properties;

    public UploadController(UploadService uploadService, CloudchunkProperties properties) {
        this.uploadService = uploadService;
        this.properties = properties;
    }

    @PostMapping("/init")
    public R<InitUploadResponse> init(@Valid @RequestBody InitUploadRequest req) {
        long userId = UserContext.getOrDefault();
        return R.ok(uploadService.init(req, userId));
    }

    @GetMapping("/progress/{fileId}")
    public R<UploadProgress> progress(@PathVariable String fileId) {
        return R.ok(uploadService.getProgress(fileId));
    }

    @PostMapping(path = "/chunk", consumes = "multipart/form-data")
    public R<ChunkUploadResponse> chunk(
            @RequestParam("fileId") String fileId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("chunkMd5") String chunkMd5,
            @RequestParam("chunkSize") long chunkSize,
            @RequestParam("file") MultipartFile file) throws IOException {

        int max = properties.getChunk().getMaxSize();
        if (file.getSize() > max) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "chunk too large");
        }
        ChunkUploadResponse resp = uploadService.uploadChunk(
                fileId, chunkIndex, chunkMd5, chunkSize, file.getInputStream());

        // 所有分片到齐 -> 自动合并
        if (resp.isAllReady() && properties.getUpload().isAutoMerge()) {
            try {
                uploadService.merge(fileId);
            } catch (Exception e) {
                // 合并失败不影响分片上传响应；前端可通过 /merge 重试
            }
        }
        return R.ok(resp);
    }

    @PostMapping("/merge/{fileId}")
    public R<MergeResult> merge(@PathVariable String fileId) {
        return R.ok(uploadService.merge(fileId));
    }

    @DeleteMapping("/{fileId}")
    public R<Void> cancel(@PathVariable String fileId) {
        uploadService.cancel(fileId);
        return R.ok();
    }
}
