package com.cloudchunk.core.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Retain only 90 days of aggregate usage. No prompt contents are logged. */
@Component
@ConditionalOnProperty(name="cloudchunk.runtime.worker-enabled",havingValue="true",matchIfMissing=true)
public class AiDailyUsageCleanupTask {
    private final JdbcTemplate jdbc;
    public AiDailyUsageCleanupTask(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(cron="\${cloudchunk.ai.budget.cleanup-cron:0 20 3 * * *}")
    public void purgeOld() {
        jdbc.update("DELETE FROM ai_model_daily_usage WHERE usage_date < ? LIMIT 1000",
            LocalDate.now(ZoneOffset.UTC).minusDays(90));
    }
}
