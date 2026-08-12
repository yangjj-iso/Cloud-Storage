package com.cloudchunk.core.upload.service;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.contract.event.BlobBroken;
import com.cloudchunk.contract.event.ChecksumRequested;
import com.cloudchunk.contract.event.IntegrationEvent;
import com.cloudchunk.contract.event.IntegrationEventPublisher;
import com.cloudchunk.contract.event.TranscodeRequested;
import com.cloudchunk.contract.storage.ObjectDeletionQueue;
import com.cloudchunk.core.file.service.FileMetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChecksumTransitionServiceTest {

    @Mock private FileMetaService fileMetaService;
    @Mock private IntegrationEventPublisher eventPublisher;
    @Mock private ObjectDeletionQueue deletionQueue;

    private ChecksumTransitionService service;
    private ChecksumRequested source;

    @BeforeEach
    void setUp() {
        service = new ChecksumTransitionService(fileMetaService, eventPublisher, deletionQueue);
        source = new ChecksumRequested();
        source.setFileId("file-1");
        source.setBucket("files");
        source.setObjectKey("objects/file-1");
        source.setMimeType("image/png");
        source.setFileSize(128L);
        source.setExpectMd5("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    @Test
    void availableTransitionEnqueuesExactlyOneTypedTranscodeEvent() {
        when(fileMetaService.transitionStatus("file-1", FileStatus.MERGED, FileStatus.AVAILABLE))
                .thenReturn(true);

        assertThat(service.markAvailableAndRequestTranscode(source)).isTrue();

        ArgumentCaptor<IntegrationEvent> event = ArgumentCaptor.forClass(IntegrationEvent.class);
        verify(eventPublisher).enqueue(org.mockito.ArgumentMatchers.eq(MqTopics.transcodeDestination(MqTopics.TAG_IMG)),
                org.mockito.ArgumentMatchers.eq("file-1"), event.capture());
        assertThat(event.getValue()).isInstanceOf(TranscodeRequested.class);
        verify(fileMetaService).updateTranscodeStatus("file-1", TranscodeStatus.QUEUED);
    }

    @Test
    void repeatedChecksumCannotPublishAnotherFollowUpEvent() {
        when(fileMetaService.transitionStatus("file-1", FileStatus.MERGED, FileStatus.AVAILABLE))
                .thenReturn(false);

        assertThat(service.markAvailableAndRequestTranscode(source)).isFalse();

        verify(eventPublisher, never()).enqueue(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void brokenTransitionPublishesNotificationAndQueuesDurableDeletion() {
        when(fileMetaService.transitionStatus("file-1", FileStatus.MERGED, FileStatus.BROKEN))
                .thenReturn(true);

        assertThat(service.markBroken(source, "checksum mismatch")).isTrue();

        ArgumentCaptor<IntegrationEvent> event = ArgumentCaptor.forClass(IntegrationEvent.class);
        verify(eventPublisher).enqueue(org.mockito.ArgumentMatchers.eq(MqTopics.BROKEN),
                org.mockito.ArgumentMatchers.eq("file-1"), event.capture());
        assertThat(event.getValue()).isInstanceOf(BlobBroken.class);
        verify(deletionQueue).enqueue("file-1", "files", "objects/file-1", false);
    }
}
