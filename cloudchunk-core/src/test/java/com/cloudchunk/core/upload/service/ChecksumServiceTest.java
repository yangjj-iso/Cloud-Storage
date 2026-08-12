package com.cloudchunk.core.upload.service;

import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.contract.event.ChecksumRequested;
import com.cloudchunk.storage.StorageStrategy;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.storage.model.GetRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChecksumServiceTest {

    @Mock private FileMetaService fileMetaService;
    @Mock private StorageStrategyFactory storageFactory;
    @Mock private ChecksumTransitionService transitionService;

    @InjectMocks
    private ChecksumService checksumService;

    private ChecksumRequested msg;
    private StorageStrategy storage;

    @BeforeEach
    void setUp() {
        msg = new ChecksumRequested();
        msg.setFileId("file-1");
        msg.setBucket("bucket");
        msg.setObjectKey("key");
        msg.setExpectMd5("expected-md5");
        msg.setMimeType("application/pdf");
        msg.setFileSize(2048L);

        storage = mock(StorageStrategy.class);
    }

    @Test
    void verify_skipsWhenMetaMissing() {
        when(fileMetaService.findById("file-1")).thenReturn(Optional.empty());

        checksumService.verify(msg);

        verifyNoInteractions(storageFactory);
    }

    @Test
    void verify_skipsWhenAlreadyAvailable() {
        FileMeta meta = new FileMeta();
        meta.setFileId("file-1");
        meta.setStatus(FileStatus.AVAILABLE);
        when(fileMetaService.findById("file-1")).thenReturn(Optional.of(meta));

        checksumService.verify(msg);

        verifyNoInteractions(storageFactory);
    }

    @Test
    void verify_success_updatesStatusAndPublishesTranscode() throws Exception {
        FileMeta meta = new FileMeta();
        meta.setFileId("file-1");
        meta.setStatus(FileStatus.MERGED);
        when(fileMetaService.findById("file-1")).thenReturn(Optional.of(meta));

        byte[] content = "test content for checksum".getBytes();
        String realMd5 = computeMd5Hex(content);
        msg.setExpectMd5(realMd5);

        when(storageFactory.current()).thenReturn(storage);
        when(storage.get(any(GetRequest.class))).thenReturn(new ByteArrayInputStream(content));

        checksumService.verify(msg);

        verify(transitionService).markAvailableAndRequestTranscode(msg);
    }

    @Test
    void verify_mismatch_marksBrokenAndPublishesBroken() throws Exception {
        FileMeta meta = new FileMeta();
        meta.setFileId("file-1");
        meta.setStatus(FileStatus.MERGED);
        when(fileMetaService.findById("file-1")).thenReturn(Optional.of(meta));

        byte[] content = "some data".getBytes();
        msg.setExpectMd5("completely-wrong-md5");

        when(storageFactory.current()).thenReturn(storage);
        when(storage.get(any(GetRequest.class))).thenReturn(new ByteArrayInputStream(content));

        checksumService.verify(msg);

        verify(transitionService).markBroken(msg, "md5 mismatch");
    }

    @Test
    void verify_mismatch_doesNotDeleteObjectInline() throws Exception {
        FileMeta meta = new FileMeta();
        meta.setFileId("file-1");
        meta.setStatus(FileStatus.MERGED);
        when(fileMetaService.findById("file-1")).thenReturn(Optional.of(meta));

        byte[] content = "some data".getBytes();
        msg.setExpectMd5("completely-wrong-md5");

        when(storageFactory.current()).thenReturn(storage);
        when(storage.get(any(GetRequest.class))).thenReturn(new ByteArrayInputStream(content));

        checksumService.verify(msg);

        verify(storage, never()).delete("bucket", "key");
        verify(transitionService).markBroken(msg, "md5 mismatch");
    }

    @Test
    void verify_streamError_rethrows() throws Exception {
        FileMeta meta = new FileMeta();
        meta.setFileId("file-1");
        meta.setStatus(FileStatus.MERGED);
        when(fileMetaService.findById("file-1")).thenReturn(Optional.of(meta));

        when(storageFactory.current()).thenReturn(storage);
        when(storage.get(any(GetRequest.class))).thenThrow(new RuntimeException("stream broken"));

        assertThatThrownBy(() -> checksumService.verify(msg))
                .isInstanceOf(RuntimeException.class);
    }

    // ==================== helper ====================

    private static String computeMd5Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
