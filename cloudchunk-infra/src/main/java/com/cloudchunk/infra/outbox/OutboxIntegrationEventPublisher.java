package com.cloudchunk.infra.outbox;

import com.cloudchunk.common.trace.TraceContext;
import com.cloudchunk.common.util.IdUtils;
import com.cloudchunk.contract.event.IntegrationEvent;
import com.cloudchunk.contract.event.IntegrationEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;

@Component
public class OutboxIntegrationEventPublisher implements IntegrationEventPublisher {

    private final OutboxEventMapper mapper;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public OutboxIntegrationEventPublisher(OutboxEventMapper mapper,
                                           ObjectMapper objectMapper,
                                           ObjectProvider<Tracer> tracerProvider) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.tracer = tracerProvider.getIfAvailable();
    }

    @Override
    public void enqueue(String destination, String aggregateKey, IntegrationEvent event) {
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            event.setEventId(IdUtils.uuid32());
        }
        if (event.getSchemaVersion() <= 0) {
            event.setSchemaVersion(1);
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(Instant.now());
        }
        Span currentSpan = tracer == null ? null : tracer.currentSpan();
        if (currentSpan != null && !currentSpan.isNoop()) {
            event.setTraceId(currentSpan.context().traceId());
            event.setParentSpanId(currentSpan.context().spanId());
            event.setTraceSampled(currentSpan.context().sampled());
        } else if (event.getTraceId() == null || event.getTraceId().isBlank()
                || "-".equals(event.getTraceId())) {
            event.setTraceId(TraceContext.setIfAbsent());
        }

        OutboxEvent row = new OutboxEvent();
        row.setEventId(event.getEventId());
        row.setEventType(event.getClass().getSimpleName());
        row.setSchemaVersion(event.getSchemaVersion());
        row.setDestination(destination);
        row.setAggregateKey(aggregateKey);
        row.setTraceId(event.getTraceId());
        row.setStatus(0);
        row.setAttemptCount(0);
        row.setNextAttemptAt(LocalDateTime.now());
        try {
            row.setPayload(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize integration event " + row.getEventType(), e);
        }
        mapper.insert(row);
    }
}
