package com.cloudchunk.mq.message;

import java.io.Serializable;
import java.time.Instant;

public class TranscodeMessage implements Serializable {

    private String msgId;
    private String fileId;
    private String mimeType;
    private String bucket;
    private String objectKey;
    private long fileSize;
    private String expectMd5;
    private int retryCount;
    private Instant producedAt;
    private String traceId;

    public TranscodeMessage() {}

    public String getMsgId() { return msgId; }
    public void setMsgId(String msgId) { this.msgId = msgId; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public String getExpectMd5() { return expectMd5; }
    public void setExpectMd5(String expectMd5) { this.expectMd5 = expectMd5; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public Instant getProducedAt() { return producedAt; }
    public void setProducedAt(Instant producedAt) { this.producedAt = producedAt; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
