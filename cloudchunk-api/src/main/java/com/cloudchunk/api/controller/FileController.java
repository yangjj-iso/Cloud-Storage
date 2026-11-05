package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.PageResult;
import com.cloudchunk.common.model.R;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.download.service.DownloadService;
import com.cloudchunk.api.monitoring.BusinessSliMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import com.cloudchunk.core.file.dto.FileView;
import com.cloudchunk.core.file.service.FileApplicationService;
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

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/file")
public class FileController {

    private final FileApplicationService fileService;
    @Autowired(required = false) private BusinessSliMetrics businessSli;

    public FileController(FileApplicationService fileService) { this.fileService = fileService; }

    /**
     * 缩略图（转码产物）。file_meta.thumbnail_url 保存的是同桶对象 key，这里鉴权后流式回源。
     */
    @GetMapping("/{fileId}/thumbnail")
    public ResponseEntity<StreamingResponseBody> thumbnail(@PathVariable String fileId) {
        long userId = UserContext.requireUserId();
        FileApplicationService.ThumbnailStream thumbnail = fileService.thumbnail(fileId, userId);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE);
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=86400");
        // 懒打开：只有真正开始写响应体时才回源，避免客户端提前断开导致对象流泄漏。
        StreamingResponseBody body = out -> {
            try (InputStream s = thumbnail.opener().open()) {
                s.transferTo(out);
                out.flush();
            }
        };
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    @GetMapping("/{fileId}")
    public R<FileView> get(@PathVariable String fileId) {
        long userId = UserContext.requireUserId();
        return R.ok(fileService.get(fileId, userId));
    }

    @GetMapping("/{fileId}/url")
    public R<Map<String, Object>> url(@PathVariable String fileId,
                                      @RequestParam(defaultValue = "1800") long ttlSeconds) {
        if (ttlSeconds <= 0 || ttlSeconds > 3600) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "ttlSeconds must be between 1 and 3600");
        }
        long userId = UserContext.requireUserId();
        DownloadService.PresignedUrl u = fileService.presign(fileId, Duration.ofSeconds(ttlSeconds), userId);
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
        long userId = UserContext.requireUserId();
        FileApplicationService.DownloadDescriptor descriptor = fileService.openDownload(fileId, range, userId);
        FileView meta = descriptor.file();

        // --- ETag 304 ---
        String etag = "\"" + meta.fileMd5() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .build();
        }

        DownloadService.DownloadStream ds = descriptor.stream();
        long length = ds.end() - ds.start() + 1;
        String contentType = meta.mimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : meta.mimeType();
        String disposition = "attachment; filename*=UTF-8''"
                + URLEncoder.encode(meta.fileName(), StandardCharsets.UTF_8);

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
            boolean complete = false;
            try (InputStream in = ds.stream()) {
                byte[] buf = new byte[256 * 1024]; // 256KB buffer
                int n;
                while ((n = in.read(buf)) > 0) {
                    outputStream.write(buf, 0, n);
                }
                outputStream.flush();
                complete = true;
            } finally {
                // Only mark completed after the actual streaming body is flushed.
                // A 200/206 header alone is not proof that bytes were delivered.
                if (businessSli != null) {
                    businessSli.operation("download_stream", complete ? "completed" : "failed");
                }
            }
        };

        return new ResponseEntity<>(body, headers, status);
    }

    @DeleteMapping("/{fileId}")
    public R<Map<String, Object>> delete(@PathVariable String fileId) {
        long userId = UserContext.requireUserId();
        var result = fileService.delete(fileId, userId);
        return R.ok(Map.of("fileId", result.fileId(), "recycled", result.recycled(), "deleted", result.deleted()));
    }

    @GetMapping
    public R<PageResult<FileView>> list(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size,
                                        @RequestParam(required = false) String mimePrefix,
                                        @RequestParam(required = false) String keyword) {
        long userId = UserContext.requireUserId();
        return R.ok(fileService.list(page, size, mimePrefix, keyword, userId));
    }

    /** 兜底响应：把常见 status 封装便于前端展示 */
    public ResponseEntity<String> notImplemented() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body("not implemented");
    }

}
