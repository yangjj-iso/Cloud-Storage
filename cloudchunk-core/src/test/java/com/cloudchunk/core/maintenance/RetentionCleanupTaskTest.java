package com.cloudchunk.core.maintenance;

import com.cloudchunk.core.CloudchunkProperties;
import com.cloudchunk.core.audit.mapper.OperationLogMapper;
import com.cloudchunk.infra.gc.ObjectGcTaskMapper;
import com.cloudchunk.infra.inbox.InboxEventMapper;
import com.cloudchunk.infra.outbox.OutboxEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionCleanupTaskTest {

    @Mock private OutboxEventMapper outboxMapper;
    @Mock private InboxEventMapper inboxMapper;
    @Mock private ObjectGcTaskMapper gcMapper;
    @Mock private OperationLogMapper operationLogMapper;

    private CloudchunkProperties properties;
    private CloudchunkProperties.Retention retention;
    private RetentionCleanupTask task;

    @BeforeEach
    void setUp() {
        properties = new CloudchunkProperties();
        retention = properties.getRetention();
        retention.setBatchSize(100);
        retention.setMaxBatchesPerRun(5);
        task = new RetentionCleanupTask(properties, outboxMapper, inboxMapper, gcMapper,
                operationLogMapper, new SimpleMeterRegistry());
    }

    private void stubEmptyExcept(Object... ignored) {
        lenient().when(outboxMapper.deletePublishedBefore(any(), anyInt())).thenReturn(0);
        lenient().when(inboxMapper.deleteProcessedBefore(any(), anyInt())).thenReturn(0);
        lenient().when(gcMapper.deleteCompletedBefore(any(), anyInt())).thenReturn(0);
        lenient().when(operationLogMapper.deleteCreatedBefore(any(), anyInt())).thenReturn(0);
    }

    @Test
    void keepsDeletingUntilABatchComesBackShortOfTheLimit() {
        stubEmptyExcept();
        when(outboxMapper.deletePublishedBefore(any(), eq(100))).thenReturn(100, 100, 42);

        task.purgeExpiredRecords();

        verify(outboxMapper, times(3)).deletePublishedBefore(any(), eq(100));
    }

    /** 积压很大时也要有可预期的清理速率上限，不能一轮把库压死。 */
    @Test
    void stopsAtTheConfiguredBatchCeilingEvenWhenMoreRowsRemain() {
        stubEmptyExcept();
        when(operationLogMapper.deleteCreatedBefore(any(), eq(100))).thenReturn(100);

        task.purgeExpiredRecords();

        verify(operationLogMapper, times(5)).deleteCreatedBefore(any(), eq(100));
    }

    @Test
    void purgesNothingWhenDisabled() {
        retention.setEnabled(false);

        task.purgeExpiredRecords();

        verify(outboxMapper, never()).deletePublishedBefore(any(), anyInt());
        verify(inboxMapper, never()).deleteProcessedBefore(any(), anyInt());
        verify(gcMapper, never()).deleteCompletedBefore(any(), anyInt());
        verify(operationLogMapper, never()).deleteCreatedBefore(any(), anyInt());
    }

    /** 把某张表的窗口设为零表示「不清理」，而不是「立刻全删」。 */
    @Test
    void aZeroWindowDisablesThatTableInsteadOfPurgingEverything() {
        stubEmptyExcept();
        retention.setInbox(Duration.ZERO);

        task.purgeExpiredRecords();

        verify(inboxMapper, never()).deleteProcessedBefore(any(), anyInt());
        verify(outboxMapper).deletePublishedBefore(any(), anyInt());
    }

    /** 一张表清理失败不应该让后面的表跟着不清理。 */
    @Test
    void aFailingTableDoesNotBlockTheRest() {
        stubEmptyExcept();
        when(outboxMapper.deletePublishedBefore(any(), anyInt()))
                .thenThrow(new IllegalStateException("deadlock"));

        task.purgeExpiredRecords();

        verify(inboxMapper).deleteProcessedBefore(any(), anyInt());
        verify(gcMapper).deleteCompletedBefore(any(), anyInt());
        verify(operationLogMapper).deleteCreatedBefore(any(), anyInt());
    }

    /** Inbox 是消费幂等键，窗口必须长于 Broker 的消息保留时长，默认值不能配得比它短。 */
    @Test
    void defaultInboxWindowOutlivesTypicalBrokerMessageRetention() {
        assertThat(retention.getInbox()).isGreaterThan(Duration.ofHours(72));
    }

    @Test
    void cutoffIsDerivedFromTheConfiguredWindow() {
        stubEmptyExcept();
        retention.setOperationLog(Duration.ofDays(30));
        LocalDateTime before = LocalDateTime.now().minusDays(30);

        task.purgeExpiredRecords();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(operationLogMapper).deleteCreatedBefore(cutoff.capture(), anyInt());
        assertThat(cutoff.getValue()).isCloseTo(before, org.assertj.core.api.Assertions.within(
                5, java.time.temporal.ChronoUnit.SECONDS));
    }
}
