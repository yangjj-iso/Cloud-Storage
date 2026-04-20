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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

    /**
     * 文件下载端点。
     *
     * <p>性能优化点（面试加分项）：</p>
     * <ol>
     *   <li><b>ETag / If-None-Match → 304</b>：用 fileMd5 做弱 ETag；命中时零流量返回。</li>
     *   <li><b>Cache-Control</b>：private + max-age，减少后续预览/缩略图重复请求。</li>
     *   <li><b>StreamingResponseBody</b>：Servlet 线程先释放，写操作交给异步 IO 线程，
     *       配合虚拟线程降低"长下载占住线程"的问题。</li>
     *   <li><b>256KB 缓冲区</b>：减少系统调用次数（默认 8KB 的 transferTo 太小）。</li>
     *   <li><b>Range 支持</b>：206 Partial Content，浏览器 / 下载管理器可多线程拉取。</li>
     * </ol>
     */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String fileId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        // --- meta lookup (later will hit Caffeine cache) ---
        FileMeta meta = fileMetaService.getAvailableOrThrow(fileId);

        // --- ETag 304 ---
        String etag = "\"" + meta.getFileMd5() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .build();
        }

        DownloadService.DownloadStream ds = downloadService.open(fileId, range);
        long length = ds.end() - ds.start() + 1;
        String contentType = meta.getMimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : meta.getMimeType();
        String disposition = "attachment; filename*=UTF-8''"
                + URLEncoder.encode(meta.getFileName(), StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(length));
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
        headers.set(HttpHeaders.ETAG, etag);
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=600");
        if (!ds.full()) {
            headers.set(HttpHeaders.CONTENT_RANGE,
                    "bytes " + ds.start() + "-" + ds.end() + "/" + ds.total());
        }

        HttpStatus status = ds.full() ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT;

        // StreamingResponseBody：写操作可在 Tomcat 异步 IO 线程执行，
        // 不阻塞 Servlet handler 线程（虚拟线程已大幅缓解，但释放越早越好）。
        StreamingResponseBody body = outputStream -> {
            try (InputStream in = ds.stream()) {
                byte[] buf = new byte[256 * 1024]; // 256KB buffer
                int n;
                while ((n = in.read(buf)) > 0) {
                    outputStream.write(buf, 0, n);
                }
                outputStream.flush();
            }
        };

        return new ResponseEntity<>(body, headers, status);
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
