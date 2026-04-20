package com.cloudchunk.core.upload.dto;

import com.cloudchunk.common.enums.UploadMode;

import java.time.LocalDateTime;
import java.util.List;

public class InitUploadResponse {

    private UploadMode mode;
    private String fileId;
    private Integer chunkSize;
    private Integer chunkTotal;
    private List<Integer> uploaded;
    private List<Integer> missing;
    private String url;
    private Integer status;
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
