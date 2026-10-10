package com.cloudchunk.api.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import java.util.function.Supplier;

/**
 * Bounded-cardinality timers; never tag user ID, file name, query or provider URL.
 * Values appear in Actuator Prometheus as seconds and histogram buckets for P95/P99.
 */
@Component
public class KnowledgeRetrievalMetrics {
    private final MeterRegistry registry;
    public KnowledgeRetrievalMetrics(MeterRegistry registry) { this.registry=registry; }

    public <T> T measure(String mode,Supplier<T> operation) {
        if(!"keyword".equals(mode) && !"hybrid".equals(mode))
            throw new IllegalArgumentException("invalid retrieval mode metric label");
        Timer.Sample sample=Timer.start(registry);
        try { return operation.get(); }
        finally {
            sample.stop(Timer.builder("cloudchunk.ai.retrieval.duration")
                .tag("mode",mode)
                .description("Full ACL-backed retrieval latency, including model embedding where enabled")
                .publishPercentileHistogram()
                .publishPercentiles(0.95,0.99)
                .register(registry));
        }
    }
}
