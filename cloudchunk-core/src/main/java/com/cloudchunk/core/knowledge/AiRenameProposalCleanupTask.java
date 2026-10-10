package com.cloudchunk.core.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/** Keep ninety days of metadata-only proposal audits; Worker role only. */
@Component
@ConditionalOnProperty(name="cloudchunk.runtime.worker-enabled",havingValue="true",matchIfMissing=true)
public class AiRenameProposalCleanupTask {
    private final JdbcTemplate jdbc;
    public AiRenameProposalCleanupTask(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Scheduled(cron="${cloudchunk.ai.agent.cleanup-cron:0 30 3 * * *}")
    public void purge() {
        jdbc.update("DELETE FROM ai_rename_proposal WHERE created_at < ? LIMIT 1000",
            LocalDateTime.now().minusDays(90));
    }
}
