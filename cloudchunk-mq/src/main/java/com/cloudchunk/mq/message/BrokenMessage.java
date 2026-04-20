package com.cloudchunk.mq.message;

import java.io.Serializable;
import java.time.Instant;

public class BrokenMessage implements Serializable {

    private String fileId;
    private String reason;
    private Instant occurredAt;
    private String traceId;

    public BrokenMessage() {}

    public static BrokenMessage of(String fileId, String reason) {
        BrokenMessage m = new BrokenMessage();
        m.fileId = fileId;
        m.reason = reason;
        m.occurredAt = Instant.now();
        return m;
    }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
