package com.cloudchunk.core.upload.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cloudchunk.common.enums.UploadSessionStatus;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.contract.event.IntegrationEventPublisher;
import com.cloudchunk.core.drive.service.UserFileService;
import com.cloudchunk.core.file.entity.FileReference;
import com.cloudchunk.core.file.mapper.FileReferenceMapper;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.quota.service.QuotaService;
import com.cloudchunk.core.upload.entity.UploadSession;
import com.cloudchunk.core.upload.mapper.UploadSessionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MergeTransactionServiceTest {

    private static final String FILE_ID = "11111111111111111111111111111111";
    private static final long FILE_SIZE = 1024L;

    @Mock private UploadSessionMapper sessionMapper;
    @Mock private FileMetaService fileMetaService;
    @Mock private FileReferenceMapper referenceMapper;
    @Mock private UserFileService userFileService;
    @Mock private IntegrationEventPublisher eventPublisher;
    @Mock private QuotaService quotaService;

    /** 单测没有 MyBatis 上下文，finalizeSuccess 内部构造 LambdaUpdateWrapper 需要实体元数据。 */
    @BeforeAll
    static void initMybatisLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UploadSession.class);
    }

    private MergeTransactionService service() {
        return new MergeTransactionService(sessionMapper, fileMetaService, referenceMapper,
                userFileService, eventPublisher, quotaService);
    }

    private UploadSession session() {
        UploadSession s = new UploadSession();
        s.setFileId(FILE_ID);
        s.setFileMd5("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        s.setFileName("test.bin");
        s.setFileSize(FILE_SIZE);
        s.setMimeType("application/octet-stream");
        s.setBucket("bucket");
        s.setObjectKey("objects/" + FILE_ID);
        s.setOwnerId(1L);
        s.setStatus(UploadSessionStatus.MERGING);
        s.setExpireAt(LocalDateTime.now().plusHours(1));
        return s;
    }

    private FileReference ref(long userId) {
        FileReference r = new FileReference();
        r.setFileId(FILE_ID);
        r.setUserId(userId);
        r.setFileName("test.bin");
        return r;
    }

    /**
     * 会话行锁必须先于参与者读取。否则本事务的第一次一致性读会在参与者取消之前建立快照，
     * 取消提交后事务内仍看得见该参与者，commitReservation 找不到预留就会抛错，
     * 结果是一个人取消把其他参与者的合并一起弄失败。
     */
    @Test
    void locksSessionRowBeforeReadingParticipants() {
        UploadSession s = session();
        when(sessionMapper.selectByFileIdForUpdate(FILE_ID)).thenReturn(s);
        when(referenceMapper.selectByFileId(FILE_ID)).thenReturn(List.of(ref(1L)));

        service().finalizeSuccess(s, "minio");

        InOrder order = inOrder(sessionMapper, referenceMapper);
        order.verify(sessionMapper).selectByFileIdForUpdate(FILE_ID);
        order.verify(referenceMapper).selectByFileId(FILE_ID);
    }

    /**
     * refCount 与配额结算必须来自同一次参与者读取。分成两次读会让已取消的参与者被计入
     * refCount，引用计数永远归不了零，物理对象再也不会被 GC 回收。
     */
    @Test
    void refCountComesFromTheSameParticipantSnapshotUsedForQuota() {
        UploadSession s = session();
        when(sessionMapper.selectByFileIdForUpdate(FILE_ID)).thenReturn(s);
        when(referenceMapper.selectByFileId(FILE_ID)).thenReturn(List.of(ref(1L), ref(2L)));

        service().finalizeSuccess(s, "minio");

        verify(fileMetaService).insert(argThat(meta -> meta.getRefCount() == 2));
        verify(quotaService).commitReservation(1L, FILE_SIZE);
        verify(quotaService).commitReservation(2L, FILE_SIZE);
        verify(referenceMapper, never()).countByFileId(anyString());
    }

    @Test
    void failsWhenSessionRowDisappearedBeforeTheLock() {
        UploadSession s = session();
        when(sessionMapper.selectByFileIdForUpdate(FILE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service().finalizeSuccess(s, "minio"))
                .isInstanceOf(BizException.class);
        verify(fileMetaService, never()).insert(argThat(meta -> true));
    }
}
