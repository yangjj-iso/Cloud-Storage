package com.cloudchunk.core.upload.dto;

import com.cloudchunk.common.enums.UploadMode;

import java.time.LocalDateTime;
import java.util.List;

public class InitUploadResponse {

    /** 初始化结果：INSTANT 秒传、UPLOAD 新上传、RESUME 续传。 */
    private UploadMode mode;
    /** 上传会话 ID，也是最终文件 ID；后续分片、确认、合并都用它关联。 */
    private String fileId;
    /** 后端确认采用的分片大小。 */
    private Integer chunkSize;
    /** 后端确认采用的分片总数。 */
    private Integer chunkTotal;
    /** 续传时已经完成的分片下标集合。 */
    private List<Integer> uploaded;
    /** 续传时仍需上传的分片下标集合。 */
    private List<Integer> missing;
    /** 秒传命中时返回的下载 URL。 */
    private String url;
    /** 兼容前端展示的状态值。 */
    private Integer status;
    /** 上传会话过期时间，过期后不能继续写分片。 */
    private LocalDateTime expireAt;

    public static InitUploadResponse instant(String fileId, String url) {
        InitUploadResponse r = new InitUploadResponse();
        r.mode = UploadMode.INSTANT;
        r.fileId = fileId;
        r.url = url;
        r.status = 2;
        return r;
    }

    public static InitUploadResponse upload(String fileId, int chunkSize, int chunkTotal, LocalDateTime expireAt) {
        InitUploadResponse r = new InitUploadResponse();
        r.mode = UploadMode.UPLOAD;
        r.fileId = fileId;
        r.chunkSize = chunkSize;
        r.chunkTotal = chunkTotal;
        r.uploaded = List.of();
        r.expireAt = expireAt;
        return r;
    }

    public static InitUploadResponse resume(String fileId, int chunkSize, int chunkTotal,
                                            List<Integer> uploaded, List<Integer> missing,
                                            LocalDateTime expireAt) {
        InitUploadResponse r = new InitUploadResponse();
        r.mode = UploadMode.RESUME;
        r.fileId = fileId;
        r.chunkSize = chunkSize;
        r.chunkTotal = chunkTotal;
        r.uploaded = uploaded;
        r.missing = missing;
        r.expireAt = expireAt;
        return r;
    }

    public UploadMode getMode() { return mode; }
    public void setMode(UploadMode mode) { this.mode = mode; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
    public Integer getChunkTotal() { return chunkTotal; }
    public void setChunkTotal(Integer chunkTotal) { this.chunkTotal = chunkTotal; }
    public List<Integer> getUploaded() { return uploaded; }
    public void setUploaded(List<Integer> uploaded) { this.uploaded = uploaded; }
    public List<Integer> getMissing() { return missing; }
    public void setMissing(List<Integer> missing) { this.missing = missing; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
}
