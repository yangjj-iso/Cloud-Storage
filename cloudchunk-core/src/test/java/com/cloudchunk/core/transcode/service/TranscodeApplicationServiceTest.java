package com.cloudchunk.core.transcode.service;

import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.contract.event.IntegrationEventPublisher;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.transcode.mapper.TranscodeRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscodeApplicationServiceTest {

    @Mock private TranscodeRecordMapper recordMapper;
    @Mock private FileMetaService fileMetaService;
    @Mock private IntegrationEventPublisher eventPublisher;

    private TranscodeApplicationService service;

    @BeforeEach
    void setUp() {
        service = new TranscodeApplicationService(recordMapper, fileMetaService, eventPublisher);
    }

    @Test
    void requestIfMissingClaimsNoneBeforeEnqueueing() {
        FileMeta meta = meta(TranscodeStatus.NONE, "image/png");
        when(fileMetaService.getOrThrow("file-1")).thenReturn(meta);
        when(fileMetaService.transitionTranscodeStatus("file-1",
                List.of(TranscodeStatus.NONE), TranscodeStatus.QUEUED)).thenReturn(true);

        assertThat(service.requestIfMissing("file-1")).isTrue();

        verify(eventPublisher).enqueue(anyString(), anyString(), any());
    }

    @Test
    void requestIfMissingDoesNotCreateAnotherEventAfterClaim() {
        when(fileMetaService.getOrThrow("file-1"))
                .thenReturn(meta(TranscodeStatus.QUEUED, "image/png"));

        assertThat(service.requestIfMissing("file-1")).isFalse();

        verify(eventPublisher, never()).enqueue(anyString(), anyString(), any());
    }

    @Test
    void requestIfMissingMarksUnsupportedMimeAsSkipped() {
        when(fileMetaService.getOrThrow("file-1"))
                .thenReturn(meta(TranscodeStatus.NONE, "application/octet-stream"));

        assertThat(service.requestIfMissing("file-1")).isFalse();

        verify(fileMetaService).transitionTranscodeStatus("file-1",
                List.of(TranscodeStatus.NONE), TranscodeStatus.SKIP);
        verify(eventPublisher, never()).enqueue(anyString(), anyString(), any());
    }

    private FileMeta meta(TranscodeStatus status, String mimeType) {
        FileMeta meta = new FileMeta();
        meta.setFileId("file-1");
        meta.setFileMd5("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        meta.setMimeType(mimeType);
        meta.setBucket("files");
        meta.setObjectKey("objects/file-1");
        meta.setFileSize(1024L);
        meta.setTranscodeStatus(status);
        return meta;
    }
}
