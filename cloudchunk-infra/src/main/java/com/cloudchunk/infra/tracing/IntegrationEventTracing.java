package com.cloudchunk.infra.tracing;

import com.cloudchunk.common.trace.TraceContext;
import com.cloudchunk.contract.event.IntegrationEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Bridges the transactional Outbox/RocketMQ boundary into the Micrometer trace.
 *
 * <p>The event payload carries the producing trace/span context because the broker
 * listener API deserializes directly to a typed payload and does not expose a
 * standard W3C carrier. Producer and consumer spans therefore remain part of the
 * original HTTP trace even when delivery happens seconds later or on another worker.</p>
 */
@Component
public class IntegrationEventTracing {

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-fA-F]{16}$");

    private final Tracer tracer;

    public IntegrationEventTracing(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable(() -> Tracer.NOOP);
    }

    public TraceScope startProducer(IntegrationEvent event, String destination) {
        TraceScope scope = start(event, "rocketmq publish " + destination, Span.Kind.PRODUCER);
        Span span = scope.span();
        if (!span.isNoop()) {
            event.setTraceId(span.context().traceId());
            event.setParentSpanId(span.context().spanId());
            event.setTraceSampled(span.context().sampled());
        }
        span.tag("messaging.system", "rocketmq")
                .tag("messaging.destination.name", destination)
                .tag("messaging.message.id", safe(event.getEventId()));
        return scope;
    }

    public TraceScope startConsumer(IntegrationEvent event, String operation) {
        TraceScope scope = start(event, operation, Span.Kind.CONSUMER);
        scope.span().tag("messaging.system", "rocketmq")
                .tag("messaging.operation", "process")
                .tag("messaging.message.id", safe(event.getEventId()));
        return scope;
    }

    private TraceScope start(IntegrationEvent event, String name, Span.Kind kind) {
        Span.Builder builder = tracer.spanBuilder().name(name).kind(kind);
        if (validParent(event)) {
            var parent = tracer.traceContextBuilder()
                    .traceId(event.getTraceId().toLowerCase())
                    .spanId(event.getParentSpanId().toLowerCase())
                    .sampled(event.getTraceSampled())
                    .build();
            builder.setParent(parent);
        } else {
            builder.setNoParent();
        }
        Span span = builder.start();
        Tracer.SpanInScope inScope = tracer.withSpan(span);
        String correlationId = !span.isNoop() ? span.context().traceId() : event.getTraceId();
        if (correlationId == null || correlationId.isBlank() || "-".equals(correlationId)) {
            TraceContext.setIfAbsent();
        } else {
            TraceContext.set(correlationId);
        }
        return new TraceScope(span, inScope);
    }

    private boolean validParent(IntegrationEvent event) {
        return event != null
                && event.getTraceId() != null && TRACE_ID.matcher(event.getTraceId()).matches()
                && event.getParentSpanId() != null && SPAN_ID.matcher(event.getParentSpanId()).matches();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    public static final class TraceScope implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope inScope;
        private boolean closed;

        private TraceScope(Span span, Tracer.SpanInScope inScope) {
            this.span = span;
            this.inScope = inScope;
        }

        public Span span() {
            return span;
        }

        public void error(Throwable error) {
            if (error != null) span.error(error);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                inScope.close();
            } finally {
                span.end();
                TraceContext.clear();
            }
        }
    }
}
