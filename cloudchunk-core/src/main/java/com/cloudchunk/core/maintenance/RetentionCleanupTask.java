package com.cloudchunk.core.maintenance;

import com.cloudchunk.core.CloudchunkProperties;
import com.cloudchunk.core.audit.mapper.OperationLogMapper;
import com.cloudchunk.infra.gc.ObjectGcTaskMapper;
import com.cloudchunk.infra.inbox.InboxEventMapper;
import com.cloudchunk.infra.outbox.OutboxEventMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 清理已经走完生命周期的运维表记录。
 * <p>
 * Outbox、Inbox、对象回收任务和审计日志的写入速率与业务写入速率同量级，但过了各自的保留窗口
 * 之后不再有任何价值。不清理的直接后果是最热的几张表无限膨胀：占用磁盘只是其一，更麻烦的是
 * 投递与消费路径上的索引随之变大、变慢，而这些正是上传链路的关键路径。
 * <p>
 * 多实例安全不需要抢占：{@code DELETE ... LIMIT} 天然幂等，两个 Worker 同时清理同一批数据时，
 * 后到的那个只会得到 0 行受影响。
 */
@Component
@ConditionalOnProperty(name = "cloudchunk.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
public class RetentionCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupTask.class);

    private final CloudchunkProperties properties;
    private final OutboxEventMapper outboxMapper;
    private final InboxEventMapper inboxMapper;
    private final ObjectGcTaskMapper gcMapper;
    private final OperationLogMapper operationLogMapper;
    private final MeterRegistry meterRegistry;

    public RetentionCleanupTask(CloudchunkProperties properties,
                                OutboxEventMapper outboxMapper,
                                InboxEventMapper inboxMapper,
                                ObjectGcTaskMapper gcMapper,
                                OperationLogMapper operationLogMapper,
                                MeterRegistry meterRegistry) {
        this.properties = properties;
        this.outboxMapper = outboxMapper;
        this.inboxMapper = inboxMapper;
        this.gcMapper = gcMapper;
        this.operationLogMapper = operationLogMapper;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelay = 3600_000, initialDelay = 300_000)
    public void purgeExpiredRecords() {
        CloudchunkProperties.Retention retention = properties.getRetention();
        if (!retention.isEnabled()) {
            return;
        }
        purge("event_outbox", retention.getOutbox(), outboxMapper::deletePublishedBefore);
        purge("event_inbox", retention.getInbox(), inboxMapper::deleteProcessedBefore);
        purge("object_gc_task", retention.getObjectGc(), gcMapper::deleteCompletedBefore);
        purge("op_log", retention.getOperationLog(), operationLogMapper::deleteCreatedBefore);
    }

    /**
     * 分批删除直到没有更多过期数据，或者达到单轮上限。
     * <p>
     * 每批各自提交，这样清理积压时不会长时间持有行锁，也不会把一次删除撑成巨大的事务。
     */
    private void purge(String table, Duration keepFor, DeleteBatch delete) {
        if (keepFor == null || keepFor.isZero() || keepFor.isNegative()) {
            return;
        }
        CloudchunkProperties.Retention retention = properties.getRetention();
        LocalDateTime before = LocalDateTime.now().minus(keepFor);
        int batchSize = Math.max(1, retention.getBatchSize());
        int removed = 0;
        for (int batch = 0; batch < Math.max(1, retention.getMaxBatchesPerRun()); batch++) {
            int affected;
            try {
                affected = delete.apply(before, batchSize);
            } catch (Exception e) {
                log.warn("retention purge failed: table={}, removedSoFar={}", table, removed, e);
                return;
            }
            removed += affected;
            if (affected < batchSize) {
                break;
            }
        }
        if (removed > 0) {
            Counter.builder("cloudchunk.retention.purged")
                    .description("Rows removed by the retention purge")
                    .tag("table", table)
                    .register(meterRegistry)
                    .increment(removed);
            log.info("retention purge: table={}, removed={}, olderThan={}", table, removed, before);
        }
    }

    @FunctionalInterface
    private interface DeleteBatch {
        int apply(LocalDateTime before, int limit);
    }
}
