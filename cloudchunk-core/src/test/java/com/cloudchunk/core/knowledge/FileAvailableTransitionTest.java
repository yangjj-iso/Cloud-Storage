package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.contract.event.ChecksumRequested;
import com.cloudchunk.contract.event.FileAvailable;
import com.cloudchunk.contract.event.IntegrationEventPublisher;
import com.cloudchunk.contract.storage.ObjectDeletionQueue;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.upload.service.ChecksumTransitionService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileAvailableTransitionTest {
    @Test void availabilityDoesNotDependOnAMediaTag() {
        var files=mock(FileMetaService.class);
        var events=mock(IntegrationEventPublisher.class);
        var service=new ChecksumTransitionService(files,events,mock(ObjectDeletionQueue.class));
        var source=new ChecksumRequested(); source.setFileId("file-a"); source.setMimeType("x-cloudchunk/unknown");
        when(files.transitionStatus("file-a",FileStatus.MERGED,FileStatus.AVAILABLE)).thenReturn(true);
        assertTrue(service.markAvailableAndRequestTranscode(source));
        verify(events).enqueue(eq(FileAvailable.TOPIC),eq("file-a"),isA(FileAvailable.class));
    }
    @Test void losingTheStateTransitionDoesNotPublishAnEvent() {
        var files=mock(FileMetaService.class);
        var events=mock(IntegrationEventPublisher.class);
        var source=new ChecksumRequested(); source.setFileId("file-a");
        assertFalse(new ChecksumTransitionService(files,events,mock(ObjectDeletionQueue.class))
                .markAvailableAndRequestTranscode(source));
        verifyNoInteractions(events);
    }
}
