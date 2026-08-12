package com.cloudchunk.integration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cloudchunk.core.CloudchunkProperties;
import com.cloudchunk.core.audit.entity.OperationLog;
import com.cloudchunk.core.audit.mapper.OperationLogMapper;
import com.cloudchunk.core.maintenance.RetentionCleanupTask;
import com.cloudchunk.infra.gc.ObjectGcTask;
import com.cloudchunk.infra.gc.ObjectGcTaskMapper;
import com.cloudchunk.infra.inbox.InboxEvent;
import com.cloudchunk.infra.inbox.InboxEventMapper;
import com.cloudchunk.infra.outbox.OutboxEvent;
import com.cloudchunk.infra.outbox.OutboxEventMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保留策略的安全性验证：只清理走完生命周期的记录，绝不碰仍在流转中的行。
 * <p>
 * 这条性质完全落在 SQL 的 WHERE 子句里，单元测试看不见。写错一个状态码的后果是静默地把待投递
 * 的事件或仍可重试的消费记录删掉——事件丢失，而且没有任何报错。
 */
@Testcontainers(disabledWithoutDocker = true)
class RetentionPurgeIT {

    private static final LocalDateTime LONG_AGO = LocalDateTime.now().minusDays(30);
    private static final LocalDateTime RECENT = LocalDateTime.now().minusHours(1);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("cloudchunk")
            .withUsername("cloudchunk")
            .withPassword("cloudchunk");

    private static HikariDataSource dataSource;
    private static OutboxEventMapper outboxMapper;
    private static InboxEventMapper inboxMapper;
    private static ObjectGcTaskMapper gcMapper;
    private static OperationLogMapper operationLogMapper;
    private static CloudchunkProperties properties;
    private static RetentionCleanupTask task;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeAll
    static void bootstrap() throws Exception {
        dataSource = MybatisTestSupport.migratedDataSource(MYSQL, 8);
        SqlSessionFactory sessionFactory = MybatisTestSupport.sessionFactory(dataSource,
                OutboxEventMapper.class, InboxEventMapper.class,
                ObjectGcTaskMapper.class, OperationLogMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory);
        outboxMapper = template.getMapper(OutboxEventMapper.class);
        inboxMapper = template.getMapper(InboxEventMapper.class);
        gcMapper = template.getMapper(ObjectGcTaskMapper.class);
        operationLogMapper = template.getMapper(OperationLogMapper.class);

        properties = new CloudchunkProperties();
        task = new RetentionCleanupTask(properties, outboxMapper, inboxMapper, gcMapper,
                operationLogMapper, new SimpleMeterRegistry());
    }

    @AfterAll
    static void shutdown() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void resetRetentionAndTables() {
        CloudchunkProperties.Retention retention = properties.getRetention();
        retention.setEnabled(true);
        retention.setOutbox(Duration.ofDays(7));
        retention.setInbox(Duration.ofDays(7));
        retention.setObjectGc(Duration.ofDays(7));
        retention.setOperationLog(Duration.ofDays(7));
        outboxMapper.delete(new QueryWrapper<>());
        inboxMapper.delete(new QueryWrapper<>());
        gcMapper.delete(new QueryWrapper<>());
        operationLogMapper.delete(new QueryWrapper<>());
    }

    private OutboxEvent outbox(int status, LocalDateTime publishedAt) {
        OutboxEvent e = new OutboxEvent();
        e.setEventId(String.format("%032d", SEQ.incrementAndGet()));
        e.setEventType("ChecksumRequested");
        e.setSchemaVersion(1);
        e.setDestination("cloudchunk-checksum");
        e.setAggregateKey("agg");
        e.setPayload("{}");
        e.setStatus(status);
        e.setAttemptCount(0);
        e.setNextAttemptAt(LONG_AGO);
        e.setCreatedAt(LONG_AGO);
        e.setPublishedAt(publishedAt);
        outboxMapper.insert(e);
        return e;
    }

    private void inbox(int status, LocalDateTime processedAt) {
        InboxEvent e = new InboxEvent();
        e.setEventId(String.format("%032d", SEQ.incrementAndGet()));
        e.setEventType("ChecksumRequested");
        e.setStatus(status);
        e.setAttemptCount(1);
        e.setLockedAt(LONG_AGO);
        e.setProcessedAt(processedAt);
        e.setCreatedAt(LONG_AGO);
        inboxMapper.insert(e);
    }

    private void gcTask(int status, LocalDateTime completedAt) {
        ObjectGcTask t = new ObjectGcTask();
        t.setBucket("cloudchunk");
        t.setObjectKey("objects/" + SEQ.incrementAndGet());
        t.setMarkFileDeleted(false);
        t.setStatus(status);
        t.setAttemptCount(0);
        t.setNextAttemptAt(LONG_AGO);
        t.setCreatedAt(LONG_AGO);
        t.setCompletedAt(completedAt);
        gcMapper.insert(t);
    }

    private void auditLog(LocalDateTime createdAt) {
        OperationLog l = new OperationLog();
        l.setUserId(1L);
        l.setOpType("upload");
        l.setResult(1);
        l.setCostMs(10);
        l.setCreatedAt(createdAt);
        operationLogMapper.insert(l);
    }

    private List<Integer> remainingOutboxStatuses() {
        return outboxMapper.selectList(new QueryWrapper<>()).stream()
                .map(OutboxEvent::getStatus).sorted().toList();
    }

    private List<Integer> remainingInboxStatuses() {
        return inboxMapper.selectList(new QueryWrapper<>()).stream()
                .map(InboxEvent::getStatus).sorted().toList();
    }

    /**
     * Outbox 只清理已投递成功的历史行。待投递、重试中和被某个 Worker 领取中的行一旦被删，
     * 对应的业务事件就永久丢失了。
     */
    @Test
    void outboxPurgeSparesEverythingThatHasNotBeenPublished() {
        outbox(2, LONG_AGO);   // published long ago -> purged
        outbox(2, RECENT);     // published recently -> kept
        outbox(0, null);       // pending
        outbox(1, null);       // claimed by a worker
        outbox(3, null);       // awaiting retry

        task.purgeExpiredRecords();

        assertThat(remainingOutboxStatuses()).containsExactly(0, 1, 2, 3);
    }

    /**
     * Inbox 只清理已完成的幂等键。失败的行仍可被 reacquire 重试，删掉等于让这条消息
     * 下次重投时被当成全新消息再执行一遍副作用。
     */
    @Test
    void inboxPurgeSparesProcessingAndFailedIdempotencyKeys() {
        inbox(1, LONG_AGO);    // done long ago -> purged
        inbox(1, RECENT);      // done recently -> kept
        inbox(0, null);        // still processing
        inbox(2, null);        // failed, still retryable

        task.purgeExpiredRecords();

        assertThat(remainingInboxStatuses()).containsExactly(0, 1, 2);
    }

    @Test
    void objectGcPurgeSparesTasksThatHaveNotDeletedTheirObjectYet() {
        gcTask(2, LONG_AGO);   // completed long ago -> purged
        gcTask(2, RECENT);     // completed recently -> kept
        gcTask(0, null);       // pending
        gcTask(3, null);       // awaiting retry

        task.purgeExpiredRecords();

        assertThat(gcMapper.selectList(new QueryWrapper<>())).hasSize(3);
    }

    @Test
    void auditLogPurgeKeepsEntriesInsideTheWindow() {
        auditLog(LONG_AGO);
        auditLog(RECENT);

        task.purgeExpiredRecords();

        List<OperationLog> remaining = operationLogMapper.selectList(new QueryWrapper<>());
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getCreatedAt()).isAfter(LocalDateTime.now().minusDays(1));
    }

    /** 积压超过单轮上限时分多轮清理，而不是一次性删光或者卡住不动。 */
    @Test
    void largeBacklogIsDrainedAcrossRunsAtTheConfiguredRate() {
        properties.getRetention().setBatchSize(10);
        properties.getRetention().setMaxBatchesPerRun(2);
        for (int i = 0; i < 50; i++) {
            outbox(2, LONG_AGO);
        }

        task.purgeExpiredRecords();
        assertThat(outboxMapper.selectList(new QueryWrapper<>())).hasSize(30);

        task.purgeExpiredRecords();
        assertThat(outboxMapper.selectList(new QueryWrapper<>())).hasSize(10);

        task.purgeExpiredRecords();
        assertThat(outboxMapper.selectList(new QueryWrapper<>())).isEmpty();
    }

    @Test
    void disabledRetentionLeavesEvenLongExpiredRowsAlone() {
        properties.getRetention().setEnabled(false);
        outbox(2, LONG_AGO);
        auditLog(LONG_AGO);

        task.purgeExpiredRecords();

        assertThat(outboxMapper.selectList(new QueryWrapper<>())).hasSize(1);
        assertThat(operationLogMapper.selectList(new QueryWrapper<>())).hasSize(1);
    }
}
