package com.cloudchunk.core.upload.dto;

public class ChunkUploadResponse {

    private String fileId;
    private int chunkIndex;
    private String etag;
    private int status;
    private boolean allReady;

    public ChunkUploadResponse() {}

    public ChunkUploadResponse(String fileId, int chunkIndex, String etag, int status, boolean allReady) {
        this.fileId = fileId;
        this.chunkIndex = chunkIndex;
        this.etag = etag;
        this.status = status;
        this.allReady = allReady;
    }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }
    public boolean isAllReady() { return allReady; }
    public void setAllReady(boolean allReady) { this.allReady = allReady; }
}
