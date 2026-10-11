package com.cloudchunk.api.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Business signals on the API, which is scraped in production.
 * No user IDs, URLs, filenames, AI prompts, or exception messages become labels.
 *
 * Async gauges are read-only 24h *current state* counts, NOT immutable
 * completion-event counters or proven message delivery rates.
 */
@Component
public class BusinessSliMetrics {
    private static final String FILE_STATES = """
        SELECT
          COALESCE(SUM(CASE WHEN status=2 THEN 1 ELSE 0 END),0),
          COALESCE(SUM(CASE WHEN status=3 THEN 1 ELSE 0 END),0)
        FROM file_meta
        WHERE updated_at >= NOW(3) - INTERVAL 24 HOUR AND status IN (2,3)
        """;
    private static final String OUTBOX_BACKLOG = """
        SELECT COUNT(*) FROM event_outbox
        WHERE status IN (0,1,3) AND created_at < NOW(3) - INTERVAL 10 MINUTE
        """;
    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    // -1 marks unavailable, distinct from a genuine zero.
    private final AtomicLong recentAvailable = new AtomicLong(-1);
    private final AtomicLong recentBroken = new AtomicLong(-1);
    private final AtomicLong delayedOutbox = new AtomicLong(-1);
    private final AtomicLong sampleValid = new AtomicLong(0);

    public BusinessSliMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        Gauge.builder("cloudchunk.business.async.available.recent", recentAvailable, AtomicLong::get)
            .description("Current AVAILABLE file_meta rows updated in last 24h, not completion events")
            .register(registry);
        Gauge.builder("cloudchunk.business.async.broken.recent", recentBroken, AtomicLong::get)
            .description("Current BROKEN file_meta rows updated in last 24h")
            .register(registry);
        Gauge.builder("cloudchunk.business.async.outbox.stale", delayedOutbox, AtomicLong::get)
            .description("Outbox events unfinished for more than 10 minutes, -1 means sample invalid")
            .register(registry);
        Gauge.builder("cloudchunk.business.async.sample.valid", sampleValid, AtomicLong::get)
            .description("1 if last database state sample succeeded, else 0")
            .register(registry);
    }

    public void operation(String operation, String result) {
        if (!("upload_merge".equals(operation) || "download_stream".equals(operation)
                || "download_url".equals(operation))) throw new IllegalArgumentException("invalid operation");
        if (!("accepted".equals(result) || "completed".equals(result)
                || "server_error".equals(result) || "client_error".equals(result)
                || "failed".equals(result))) throw new IllegalArgumentException("invalid result");
        Counter.builder("cloudchunk.business.operation")
                .tag("operation", operation).tag("result", result)
                .description("Bounded business operation outcomes; merge accepted is not file availability")
                .register(registry).increment();
    }

    public void aiRetrieval(String mode) {
        String kind = switch (mode == null ? "" : mode) {
            case "KEYWORD_FALLBACK", "QDRANT_FALLBACK" -> "fallback";
            case "SEMANTIC_DISABLED", "SEMANTIC_NOT_INDEXED", "QDRANT_WARMING" -> "not_ready";
            case "HYBRID_RRF_LIMITED", "QDRANT_HYBRID_RRF_LIMITED" -> "semantic";
            default -> "other";
        };
        Counter.builder("cloudchunk.business.ai.retrieval")
                .tag("kind", kind).register(registry).increment();
    }

    @Scheduled(initialDelayString = "${cloudchunk.observability.business-sli-sample-ms:60000}",
               fixedDelayString = "${cloudchunk.observability.business-sli-sample-ms:60000}")
    public void sampleAsync() {
        // Fail closed: a partial read must invalidate ALL related gauges.
        try {
            long[] rows = jdbc.queryForObject(FILE_STATES,
                    (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)});
            Long backlog = jdbc.queryForObject(OUTBOX_BACKLOG, Long.class);
            if (rows == null || rows.length != 2 || backlog == null
                    || rows[0] < 0 || rows[1] < 0 || backlog < 0)
                throw new IllegalStateException("incomplete SLI");
            recentAvailable.set(rows[0]);
            recentBroken.set(rows[1]);
            delayedOutbox.set(backlog);
            sampleValid.set(1);
        } catch (Exception ignored) {
            recentAvailable.set(-1);
            recentBroken.set(-1);
            delayedOutbox.set(-1);
            sampleValid.set(0);
        }
    }
}
