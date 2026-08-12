package com.cloudchunk.contract.event;

import java.time.Instant;

/**
 * Versioned event contract shared by the application and messaging adapters.
 * Event IDs are stable across retries and are the consumer idempotency key.
 */
public interface IntegrationEvent {

    String getEventId();

    void setEventId(String eventId);

    int getSchemaVersion();

    void setSchemaVersion(int schemaVersion);

    Instant getOccurredAt();

    void setOccurredAt(Instant occurredAt);

    String getTraceId();

    void setTraceId(String traceId);

    String getParentSpanId();

    void setParentSpanId(String parentSpanId);

    Boolean getTraceSampled();

    void setTraceSampled(Boolean traceSampled);
}
