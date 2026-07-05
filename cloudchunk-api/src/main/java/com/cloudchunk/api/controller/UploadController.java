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
        // 初始化阶段不接收文件内容，只接收文件元数据和分片计划。
        // userId 由 TraceFilter 从 Bearer token 写入 UserContext，用于配额检查和上传归属。
        long userId = UserContext.requireUserId();
        return R.ok(uploadService.init(req, userId));
    }

    @GetMapping("/progress/{fileId}")
    public R<UploadProgress> progress(@PathVariable String fileId) {
        long userId = UserContext.requireUserId();
        return R.ok(uploadService.getProgress(fileId, userId));
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

        // multipart 路径是兼容入口：Spring/Tomcat 会先解析 multipart，
        // 大分片通常会落到临时文件，再由 uploadService 读取输入流写入存储。
        int max = properties.getChunk().getMaxSize();
        if (file.getSize() > max) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "chunk too large");
        }
        if (file.getSize() != chunkSize) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER,
                    "chunkSize mismatch: claimed=" + chunkSize + ", actual=" + file.getSize());
        }
        long userId = UserContext.requireUserId();
        ChunkUploadResponse resp = uploadService.uploadChunk(
                fileId, chunkIndex, chunkMd5, userId, chunkSize, file.getInputStream());
        autoMergeIfReady(fileId, resp, userId);
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

        // 裸流路径是后端代理上传的高性能入口。
        // 请求体就是一个分片的二进制内容，元数据放在 query 参数里。
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
            // 不 readAllBytes，不把分片整体放进 JVM 堆。
            // uploadService 会把这个流直接传给存储层，并同时计算 MD5。
            long userId = UserContext.requireUserId();
            ChunkUploadResponse resp = uploadService.uploadChunk(
                    fileId, chunkIndex, chunkMd5, userId, chunkSize, in);
            autoMergeIfReady(fileId, resp, userId);
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
        // 直传路径准备阶段：后端只生成签名 URL，实际分片数据由浏览器直接 PUT 到 MinIO。
        long userId = UserContext.requireUserId();
        return R.ok(uploadService.presignChunks(fileId, indices, userId));
    }

    /**
     * 前端直传 MinIO 成功后的确认回调：校验对象存在 → 记录进度。
     */
    @PostMapping("/confirm")
    public R<ChunkUploadResponse> confirmChunk(
            @RequestParam("fileId") String fileId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam(value = "chunkMd5", required = false, defaultValue = "") String chunkMd5) {
        // 直传路径确认阶段：这里不接收二进制内容。
        // 后端只去存储层确认 partKey 是否存在，然后记录分片完成。
        long userId = UserContext.requireUserId();
        ChunkUploadResponse resp = uploadService.confirmChunk(fileId, chunkIndex, chunkMd5, userId);
        autoMergeIfReady(fileId, resp, userId);
        return R.ok(resp);
    }

    private void autoMergeIfReady(String fileId, ChunkUploadResponse resp, long userId) {
        // WebSocket 实时推送分片进度
        wsProgress.broadcast(fileId, resp);
        if (resp.isAllReady() && properties.getUpload().isAutoMerge()) {
            try {
                // 最后一片完成后可自动合并。
                // 合并失败不影响当前分片响应，前端仍可显式调用 /merge/{fileId} 重试。
                uploadService.merge(fileId, userId);
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
        // 上传前优化：前端告诉后端缺失分片的 MD5。
        // 后端若找到已存在的相同内容分片，就在存储端 copy，避免客户端再上传。
        long userId = UserContext.requireUserId();
        return R.ok(uploadService.dedupChunks(fileId, chunkMd5Map, userId));
    }

    @PostMapping("/merge/{fileId}")
    public R<MergeResult> merge(@PathVariable String fileId) {
        // 合并阶段：所有分片完成后，把临时 part 对象组合成最终文件对象。
        // 该接口允许前端主动重试，UploadService 内部用锁和状态保证幂等。
        long userId = UserContext.requireUserId();
        return R.ok(uploadService.merge(fileId, userId));
    }

    @DeleteMapping("/{fileId}")
    public R<Void> cancel(@PathVariable String fileId) {
        long userId = UserContext.requireUserId();
        uploadService.cancel(fileId, userId);
        return R.ok();
    }
}
