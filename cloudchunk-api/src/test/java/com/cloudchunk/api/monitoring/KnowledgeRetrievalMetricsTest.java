package com.cloudchunk.api.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeRetrievalMetricsTest {
    @Test void recordsOnlyBoundedModesEvenWhenSearchFails() {
        try(var registry=new SimpleMeterRegistry()) {
            var metrics=new KnowledgeRetrievalMetrics(registry);
            assertEquals("ok",metrics.measure("keyword",()->"ok"));
            assertThrows(IllegalStateException.class,
                ()->metrics.measure("hybrid",()->{throw new IllegalStateException("transient");}));
            assertEquals(1,registry.get("cloudchunk.ai.retrieval.duration")
                .tag("mode","keyword").timer().count());
            assertEquals(1,registry.get("cloudchunk.ai.retrieval.duration")
                .tag("mode","hybrid").timer().count());
            assertThrows(IllegalArgumentException.class,
                ()->metrics.measure("document-content-secret",()->"not run"));
        }
    }
}
