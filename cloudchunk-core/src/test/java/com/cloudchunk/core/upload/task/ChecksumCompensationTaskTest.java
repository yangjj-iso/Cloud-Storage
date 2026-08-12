package com.cloudchunk.core.upload.task;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.contract.event.IntegrationEvent;
import com.cloudchunk.contract.event.IntegrationEventPublisher;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.mapper.FileMetaMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChecksumCompensationTaskTest {

    private static final String FILE_ID = "11111111111111111111111111111111";

    @Mock private FileMetaMapper fileMetaMapper;
    @Mock private IntegrationEventPublisher eventPublisher;

    @InjectMocks private ChecksumCompensationTask task;

    private FileMeta pendingFile() {
        FileMeta m = new FileMeta();
        m.setFileId(FILE_ID);
        m.setBucket("bucket");
        m.setObjectKey("objects/" + FILE_ID);
        m.setFileMd5("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        m.setMimeType("application/octet-stream");
        m.setFileSize(1024L);
        return m;
    }

    /**
     * 每个 Worker 都会跑这个补偿任务并扫到同一批文件。事件 ID 必须由文件和时间窗决定，
     * 否则 event_inbox 无法识别它们是同一件事，校验消费者会对同一个文件重复整体回源算 MD5。
     */
    @Test
    void usesDeterministicEventIdSoPeerWorkersCollapseToOneEvent() {
        when(fileMetaMapper.selectList(any())).thenReturn(List.of(pendingFile()));

        task.compensate();
        task.compensate();

        ArgumentCaptor<IntegrationEvent> captor = ArgumentCaptor.forClass(IntegrationEvent.class);
        verify(eventPublisher, times(2)).enqueue(eq(MqTopics.CHECKSUM), eq(FILE_ID), captor.capture());
        List<IntegrationEvent> events = captor.getAllValues();
        assertThat(events.get(0).getEventId()).isEqualTo(events.get(1).getEventId());
    }

    /** event_outbox.event_id 是 CHAR(32)，派生 ID 超长会被 MySQL 截断或拒绝。 */
    @Test
    void derivedEventIdFitsTheOutboxColumn() {
        when(fileMetaMapper.selectList(any())).thenReturn(List.of(pendingFile()));

        task.compensate();

        ArgumentCaptor<IntegrationEvent> captor = ArgumentCaptor.forClass(IntegrationEvent.class);
        verify(eventPublisher).enqueue(any(), any(), captor.capture());
        assertThat(captor.getValue().getEventId()).hasSize(32).matches("[0-9a-f]{32}");
    }

    /** 撞上唯一键说明同窗口内已有其他 Worker 投递过，是预期结果而不是故障。 */
    @Test
    void duplicateEventIsTreatedAsPeerDeduplicationNotFailure() {
        when(fileMetaMapper.selectList(any())).thenReturn(List.of(pendingFile()));
        doThrow(new DuplicateKeyException("uk_outbox_event_id"))
                .when(eventPublisher).enqueue(any(), any(), any());

        assertThatCode(() -> task.compensate()).doesNotThrowAnyException();
    }
}
