package com.cloudchunk.api.controller;

import com.cloudchunk.api.ws.UploadProgressHandler;
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
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
import java.io.InputStream;

@RestController
@RequestMapping("/api/v1/upload")
public class UploadController {

    private final UploadService uploadService;
    private final CloudchunkProperties properties;
    private final UploadProgressHandler wsProgress;

    public UploadController(UploadService uploadService, CloudchunkProperties properties,
                            UploadProgressHandler wsProgress) {
        this.uploadService = uploadService;
        this.properties = properties;
        this.wsProgress = wsProgress;
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

    /**
     * 分片上传（多部件 form）。保留做兼容。
     * <p>注意：multipart 会把整片先落到 Tomcat 临时文件，多一次磁盘 round-trip。
     * 高性能路径推荐用 {@link #chunkRaw}。</p>
     */
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
        if (file.getSize() != chunkSize) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER,
                    "chunkSize mismatch: claimed=" + chunkSize + ", actual=" + file.getSize());
        }
        ChunkUploadResponse resp = uploadService.uploadChunk(
                fileId, chunkIndex, chunkMd5, chunkSize, file.getInputStream());
        autoMergeIfReady(fileId, resp);
        return R.ok(resp);
    }

    /**
     * 分片上传（高性能路径：裸 {@code application/octet-stream} 请求体）。
     * 请求体直接是分片二进制；元数据通过 query 参数传递。
     *
     * <p>相较 multipart 的优势：</p>
     * <ul>
     *   <li>Tomcat 不解析 multipart，不产生临时文件，省一次磁盘写+读；</li>
     *   <li>我们直接拿到 {@link HttpServletRequest#getInputStream()} 边读边喂给 MinIO，
     *       真正的"零落地"流式上传；</li>
     *   <li>配合 Jetty/Undertow/Tomcat NIO 连接器，进一步降低内存占用。</li>
     * </ul>
     */
    @PostMapping(path = "/chunk", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public R<ChunkUploadResponse> chunkRaw(
            @RequestParam("fileId") String fileId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("chunkMd5") String chunkMd5,
            @RequestParam("chunkSize") long chunkSize,
            HttpServletRequest request) throws IOException {

        int max = properties.getChunk().getMaxSize();
        if (chunkSize <= 0 || chunkSize > max) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "chunk too large or invalid");
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength >= 0 && contentLength != chunkSize) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER,
                    "chunkSize mismatch: claimed=" + chunkSize + ", contentLength=" + contentLength);
        }
        try (InputStream in = request.getInputStream()) {
            ChunkUploadResponse resp = uploadService.uploadChunk(
                    fileId, chunkIndex, chunkMd5, chunkSize, in);
            autoMergeIfReady(fileId, resp);
            return R.ok(resp);
        }
    }

    /**
     * 批量生成 presigned PUT URL，前端可直传 MinIO 跳过后端数据路径。
     */
    @GetMapping("/presign/{fileId}")
    public R<java.util.Map<Integer, String>> presignChunks(
            @PathVariable String fileId,
            @RequestParam("indices") java.util.List<Integer> indices) {
        return R.ok(uploadService.presignChunks(fileId, indices));
    }

    /**
     * 前端直传 MinIO 成功后的确认回调：校验对象存在 → 记录进度。
     */
    @PostMapping("/confirm")
    public R<ChunkUploadResponse> confirmChunk(
            @RequestParam("fileId") String fileId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam(value = "chunkMd5", required = false, defaultValue = "") String chunkMd5) {
        ChunkUploadResponse resp = uploadService.confirmChunk(fileId, chunkIndex, chunkMd5);
        autoMergeIfReady(fileId, resp);
        return R.ok(resp);
    }

    private void autoMergeIfReady(String fileId, ChunkUploadResponse resp) {
        // WebSocket 实时推送分片进度
        wsProgress.broadcast(fileId, resp);
        if (resp.isAllReady() && properties.getUpload().isAutoMerge()) {
            try {
                uploadService.merge(fileId);
            } catch (Exception e) {
                // 合并失败不影响分片上传响应；前端可通过 /merge 重试
            }
        }
    }

    /**
     * 分片级去重：前端传入 chunkIndex → chunkMd5 映射，后端查找已有相同内容的分片，
     * 通过 MinIO 服务端拷贝跳过上传。返回已去重的 chunk index 列表。
     */
    @PostMapping("/dedup/{fileId}")
    public R<java.util.List<Integer>> dedupChunks(
            @PathVariable String fileId,
            @RequestBody java.util.Map<Integer, String> chunkMd5Map) {
        return R.ok(uploadService.dedupChunks(fileId, chunkMd5Map));
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
