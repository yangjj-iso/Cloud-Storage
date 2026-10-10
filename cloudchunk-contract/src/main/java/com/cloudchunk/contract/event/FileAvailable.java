package com.cloudchunk.contract.event;

import lombok.Data;
import java.time.Instant;

/** Storage has committed AVAILABLE. Consumers resolve fresh metadata, never trust object keys in messages. */
@Data
public class FileAvailable implements IntegrationEvent {
    public static final String TOPIC = "cc-file-available";
    private String eventId;
    private int schemaVersion = 1;
    private Instant occurredAt;
    private String traceId;
    private String parentSpanId;
    private Boolean traceSampled;
    private String fileId;

    public static FileAvailable of(String fileId) {
        FileAvailable event = new FileAvailable();
        event.setFileId(fileId);
        return event;
    }
}
