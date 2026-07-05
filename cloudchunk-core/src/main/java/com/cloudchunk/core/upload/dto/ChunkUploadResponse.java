package com.cloudchunk.core.upload.dto;

public class ChunkUploadResponse {

    /** 当前上传会话/文件 ID。 */
    private String fileId;
    /** 本次完成的分片下标，从 0 开始。 */
    private int chunkIndex;
    /** 存储层返回的对象 ETag；直传确认路径可能为空或来自 statObject。 */
    private String etag;
    /** 分片状态，当前 1 表示完成。 */
    private int status;
    /** 是否所有分片都已完成，可用于触发自动合并。 */
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
