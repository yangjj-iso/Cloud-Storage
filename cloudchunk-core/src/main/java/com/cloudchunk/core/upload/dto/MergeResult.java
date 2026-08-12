package com.cloudchunk.core.upload.dto;

public class MergeResult {

    private String fileId;
    private String status;

    public MergeResult() {}

    public MergeResult(String fileId, String status) {
        this.fileId = fileId;
        this.status = status;
    }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
