package com.cloudchunk.core.upload.dto;

public class MergeResult {

    private String fileId;
    private String status;
    private String objectKey;
    private String etag;

    public MergeResult() {}

    public MergeResult(String fileId, String status, String objectKey, String etag) {
        this.fileId = fileId;
        this.status = status;
        this.objectKey = objectKey;
        this.etag = etag;
    }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
}
