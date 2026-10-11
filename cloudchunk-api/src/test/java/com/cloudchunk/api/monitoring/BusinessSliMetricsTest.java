package com.cloudchunk.api.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class BusinessSliMetricsTest {
    @Test void noHighCardinalityLabelsAndFallbacksAreCategorized() {
        try (var registry = new SimpleMeterRegistry()) {
            var metric = new BusinessSliMetrics(registry, mock(JdbcTemplate.class));
            metric.operation("upload_merge", "accepted");
            metric.operation("download_stream", "failed");
            metric.aiRetrieval("KEYWORD_FALLBACK");
            metric.aiRetrieval("QDRANT_HYBRID_RRF_LIMITED");
            assertThat(registry.get("cloudchunk.business.operation").tag("operation","upload_merge")
                    .tag("result","accepted").counter().count()).isEqualTo(1);
            assertThat(registry.get("cloudchunk.business.ai.retrieval").tag("kind","fallback")
                    .counter().count()).isEqualTo(1);
            assertThatThrownBy(() -> metric.operation("user-secret-id","accepted"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(BusinessSliHttpFilter.operationFor("GET","/api/v1/file/sensitive/download"))
                    .isNull();
            assertThat(BusinessSliHttpFilter.operationFor("POST",
                    "/api/v1/upload/merge/0123456789abcdef0123456789abcdef")).isEqualTo("upload_merge");
        }
    }

    @Test void missingAsyncDataIsNeverTreatedAsZero() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        try (var registry = new SimpleMeterRegistry()) {
            var metric = new BusinessSliMetrics(registry, jdbc);
            metric.sampleAsync();
            assertThat(registry.get("cloudchunk.business.async.sample.valid").gauge().value()).isZero();
            assertThat(registry.get("cloudchunk.business.async.outbox.stale").gauge().value()).isEqualTo(-1);
        }
    }
}
