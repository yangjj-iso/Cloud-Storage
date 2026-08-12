package com.cloudchunk.contract.event;

import java.time.Instant;

public class ChecksumRequested implements IntegrationEvent {

    private String eventId;
    private int schemaVersion = 1;
    private Instant occurredAt;
    private String traceId;
    private String parentSpanId;
    private Boolean traceSampled;
    private String fileId;
    private String bucket;
    private String objectKey;
    private String expectMd5;
    private String mimeType;
    private long fileSize;

    public ChecksumRequested() {}

    @Override public String getEventId() { return eventId; }
    @Override public void setEventId(String eventId) { this.eventId = eventId; }
    @Override public int getSchemaVersion() { return schemaVersion; }
    @Override public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    @Override public Instant getOccurredAt() { return occurredAt; }
    @Override public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    @Override public String getTraceId() { return traceId; }
    @Override public void setTraceId(String traceId) { this.traceId = traceId; }
    @Override public String getParentSpanId() { return parentSpanId; }
    @Override public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }
    @Override public Boolean getTraceSampled() { return traceSampled; }
    @Override public void setTraceSampled(Boolean traceSampled) { this.traceSampled = traceSampled; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getExpectMd5() { return expectMd5; }
    public void setExpectMd5(String expectMd5) { this.expectMd5 = expectMd5; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
}
