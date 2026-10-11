package com.cloudchunk.api.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class OperationsOverviewServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode metric(double n) throws Exception {
        String value = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"value\":["
                + Instant.now().getEpochSecond() + ",\"" + n + "\"]}]}}";
        return mapper.readTree(value);
    }

    @Test void goodSloAndSanitizedActiveAlerts() {
        try {
            var service = new OperationsOverviewService("http://prometheus:9090",
                    "http://alertmanager:9093", uri -> {
                        if (uri.getPath().endsWith("/alerts")) return mapper.readTree("""
                           [{"fingerprint":"1234567890abcdef","labels":{"alertname":"CloudChunkApiHigh5xx",
                            "severity":"critical","token":"NEVER-EXPOSE"},"status":{"state":"active"},
                            "startsAt":"2026-10-01T01:00:00Z","annotations":{"secret":"NEVER-EXPOSE"}},
                            {"fingerprint":"bad","labels":{"alertname":"UntrustedAlert"}}]
                           """);
                        String q = java.net.URLDecoder.decode(uri.getRawQuery(), java.nio.charset.StandardCharsets.UTF_8);
                        if (q.contains("count_over_time")) return metric(40320);
                        if (q.contains("status=~")) return metric(1);
                        return metric(1000);
                    });
            var result = service.snapshot();
            assertThat(result.status()).isEqualTo("READ_ONLY");
            assertThat(result.slo().state()).isEqualTo("OBSERVED");
            assertThat(result.slo().observedAvailability()).isEqualTo(0.999);
            assertThat(result.alerts()).hasSize(1);
            assertThat(result.toString()).doesNotContain("NEVER-EXPOSE");
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void outageOrMissingHistoryNeverProducesGreen() throws Exception {
        var missing = new OperationsOverviewService("http://prometheus:9090",
                "http://alertmanager:9093", uri -> { throw new IllegalStateException("password-leak"); });
        assertThat(missing.snapshot().slo().state()).isEqualTo("UNAVAILABLE");
        assertThat(missing.snapshot().alertSource()).isEqualTo("UNAVAILABLE");
        assertThat(missing.snapshot().toString()).doesNotContain("password-leak");

        var small = new OperationsOverviewService("http://prometheus:9090", "", uri -> {
            String q = java.net.URLDecoder.decode(uri.getRawQuery(), java.nio.charset.StandardCharsets.UTF_8);
            if (q.contains("count_over_time")) return metric(10);
            return metric(q.contains("status=~") ? 0 : 20);
        });
        assertThat(small.snapshot().slo().state()).isEqualTo("WARMING");
    }

    @Test void configuredEndpointsCannotUseArbitraryLocalhostUrls() {
        assertThat(OperationsOverviewService.safeEndpoint("http://localhost:9090", "prometheus")).isEmpty();
        assertThat(OperationsOverviewService.safeEndpoint("http://prometheus:9090/a", "prometheus")).isEmpty();
        assertThat(OperationsOverviewService.safeEndpoint("http://localhost:9093", "alertmanager")).isEmpty();
        assertThat(OperationsOverviewService.safeEndpoint("http://prometheus:9090", "prometheus"))
                .isEqualTo("http://prometheus:9090");
    }
}
