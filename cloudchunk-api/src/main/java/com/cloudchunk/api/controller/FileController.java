package com.cloudchunk.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.model.PageResult;
import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.download.service.DownloadService;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.mapper.FileMetaMapper;
import com.cloudchunk.core.file.service.FileMetaService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/file")
public class FileController {

    private final FileMetaService fileMetaService;
    private final DownloadService downloadService;
    private final FileMetaMapper fileMetaMapper;

    public FileController(FileMetaService fileMetaService,
                          DownloadService downloadService,
                          FileMetaMapper fileMetaMapper) {
        this.fileMetaService = fileMetaService;
        this.downloadService = downloadService;
        this.fileMetaMapper = fileMetaMapper;
    }

    @GetMapping("/{fileId}")
    public R<FileMeta> get(@PathVariable String fileId) {
        return R.ok(fileMetaService.getOrThrow(fileId));
    }

    @GetMapping("/{fileId}/url")
    public R<Map<String, Object>> url(@PathVariable String fileId,
                                      @RequestParam(defaultValue = "1800") long ttlSeconds) {
        DownloadService.PresignedUrl u = downloadService.presign(fileId, Duration.ofSeconds(ttlSeconds));
        return R.ok(Map.of("url", u.url(),
                "expireInSeconds", u.ttl() == null ? ttlSeconds : u.ttl().toSeconds()));
    }

    @GetMapping("/{fileId}/download")
    public void download(@PathVariable String fileId,
                         @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
                         HttpServletResponse response) throws IOException {

        DownloadService.DownloadStream ds = downloadService.open(fileId, range);
        FileMeta meta = ds.meta();
        long length = ds.end() - ds.start() + 1;

        response.setStatus(ds.full() ? HttpStatus.OK.value() : HttpStatus.PARTIAL_CONTENT.value());
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(length));
        response.setHeader(HttpHeaders.CONTENT_TYPE,
                meta.getMimeType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : meta.getMimeType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(meta.getFileName(), StandardCharsets.UTF_8));
        if (!ds.full()) {
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    "bytes " + ds.start() + "-" + ds.end() + "/" + ds.total());
        }

        try (InputStream in = ds.stream(); OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    @DeleteMapping("/{fileId}")
    public R<Map<String, Object>> delete(@PathVariable String fileId) {
        fileMetaService.decRefCount(fileId);
        FileMeta meta = fileMetaService.getOrThrow(fileId);
        boolean actuallyDeleted = meta.getRefCount() != null && meta.getRefCount() <= 0;
        if (actuallyDeleted) {
            fileMetaService.markDeleted(fileId);
        }
        return R.ok(Map.of("fileId", fileId, "deleted", actuallyDeleted));
    }

    @GetMapping
    public R<PageResult<FileMeta>> list(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size,
                                        @RequestParam(required = false) String mimePrefix,
                                        @RequestParam(required = false) String keyword) {
        long userId = UserContext.getOrDefault();
        long safeSize = Math.min(Math.max(size, 1), 100);

        Page<FileMeta> p = new Page<>(Math.max(page, 1), safeSize);
        LambdaQueryWrapper<FileMeta> w = new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getOwnerId, userId)
                .ne(FileMeta::getStatus, FileStatus.DELETED)
                .orderByDesc(FileMeta::getCreatedAt);
        if (mimePrefix != null && !mimePrefix.isBlank()) {
            w.likeRight(FileMeta::getMimeType, mimePrefix);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.like(FileMeta::getFileName, keyword);
        }
        Page<FileMeta> result = fileMetaMapper.selectPage(p, w);
        return R.ok(new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords()));
    }

    /** 兜底响应：把常见 status 封装便于前端展示 */
    public ResponseEntity<String> notImplemented() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body("not implemented");
    }
}
