package com.cloudchunk.contract.event;

import java.time.Instant;

public class BlobBroken implements IntegrationEvent {

    private String eventId;
    private int schemaVersion = 1;
    private Instant occurredAt;
    private String traceId;
    private String parentSpanId;
    private Boolean traceSampled;
    private String fileId;
    private String reason;

    public BlobBroken() {}

    public static BlobBroken of(String fileId, String reason) {
        BlobBroken event = new BlobBroken();
        event.setFileId(fileId);
        event.setReason(reason);
        return event;
    }

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
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
