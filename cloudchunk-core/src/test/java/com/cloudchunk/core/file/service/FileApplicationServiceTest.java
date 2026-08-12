package com.cloudchunk.core.file.service;

import com.cloudchunk.core.download.service.DownloadService;
import com.cloudchunk.core.drive.service.UserFileService;
import com.cloudchunk.core.file.mapper.FileMetaMapper;
import com.cloudchunk.core.quota.service.QuotaService;
import com.cloudchunk.storage.StorageStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileApplicationServiceTest {

    @Mock private FileMetaService fileMetaService;
    @Mock private DownloadService downloadService;
    @Mock private FileMetaMapper fileMetaMapper;
    @Mock private QuotaService quotaService;
    @Mock private StorageStrategyFactory storageFactory;
    @Mock private UserFileService userFileService;

    private FileApplicationService service;

    @BeforeEach
    void setUp() {
        service = new FileApplicationService(fileMetaService, downloadService, fileMetaMapper,
                quotaService, storageFactory, userFileService);
    }

    @Test
    void repeatedDeleteKeepsRecycleBinReferenceAndQuota() {
        when(userFileService.recycleByFileId(7L, "file-1")).thenReturn(0);
        when(userFileService.hasAnyFileEntry(7L, "file-1")).thenReturn(true);

        FileApplicationService.DeleteResult result = service.delete("file-1", 7L);

        assertThat(result.recycled()).isTrue();
        assertThat(result.deleted()).isFalse();
        verify(fileMetaService, never()).removeReference("file-1", 7L);
        verify(quotaService, never()).subUsed(anyLong(), anyLong());
    }
}
