package com.cloudchunk.core.upload.service;

import com.cloudchunk.common.concurrent.BoundedVirtualThreadExecutor;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.enums.UploadMode;
import com.cloudchunk.common.enums.UploadSessionStatus;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.CloudchunkProperties;
import com.cloudchunk.core.drive.service.UserFileService;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.quota.service.QuotaService;
import com.cloudchunk.core.upload.dto.ChunkUploadResponse;
import com.cloudchunk.core.upload.dto.InitUploadRequest;
import com.cloudchunk.core.upload.dto.InitUploadResponse;
import com.cloudchunk.core.upload.entity.UploadSession;
import com.cloudchunk.core.upload.mapper.ChunkRecordMapper;
import com.cloudchunk.core.upload.mapper.UploadSessionMapper;
import com.cloudchunk.infra.redis.RedisLock;
import com.cloudchunk.infra.redis.RedisService;
import com.cloudchunk.mq.producer.ChecksumProducer;
import com.cloudchunk.storage.StorageStrategy;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.storage.model.ComposeRequest;
import com.cloudchunk.storage.model.ComposeResult;
import com.cloudchunk.storage.model.PutRequest;
import com.cloudchunk.storage.model.PutResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    private static final String FILE_ID = "11111111111111111111111111111111";
    private static final String FILE_MD5 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CHUNK_MD5 = "5eb63bbbe01eeed093cb22bb8f5acdc3";
    private static final String WRONG_MD5 = "00000000000000000000000000000000";

    @Mock private UploadSessionMapper sessionMapper;
    @Mock private ChunkRecordMapper chunkMapper;
    @Mock private FileMetaService fileMetaService;
    @Mock private QuotaService quotaService;
    @Mock private StorageStrategyFactory storageFactory;
    @Mock private ProgressStore progress;
    @Mock private RedisService redis;
    @Mock private RedisLock redisLock;
    @Mock private ChecksumProducer checksumProducer;
    @Mock private CloudchunkProperties properties;
    @Mock private MergeTransactionService mergeTx;
    @Mock private UserFileService userFileService;
    @Mock private BoundedVirtualThreadExecutor ioExecutor;
    @Mock private BoundedVirtualThreadExecutor cleanupExecutor;

    private UploadService uploadService;

    private InitUploadRequest request;
    private CloudchunkProperties.Chunk chunkProps;

    @BeforeEach
    void setUp() {
        uploadService = new UploadService(
                sessionMapper,
                chunkMapper,
                fileMetaService,
                quotaService,
                storageFactory,
                progress,
                redis,
                redisLock,
                checksumProducer,
                properties,
                mergeTx,
                userFileService,
                ioExecutor,
                cleanupExecutor);

        chunkProps = new CloudchunkProperties.Chunk();
        chunkProps.setMinSize(1024 * 1024);
        chunkProps.setMaxSize(100 * 1024 * 1024);
        chunkProps.setSessionTtl(Duration.ofHours(24));
        lenient().when(properties.getChunk()).thenReturn(chunkProps);

        request = new InitUploadRequest();
        request.setFileMd5(FILE_MD5);
        request.setFileName("test.txt");
        request.setFileSize(10 * 1024 * 1024L);
        request.setChunkSize(5 * 1024 * 1024);
        request.setChunkTotal(2);
    }

    // ==================== init tests ====================

    @Test
    void init_instantUpload_whenMd5Exists() {
        when(redisLock.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        FileMeta meta = new FileMeta();
        meta.setFileId("existing-file-id");
        meta.setFileSize(10 * 1024 * 1024L);
        meta.setBucket("bucket");
        meta.setObjectKey("key");
        when(fileMetaService.findReusableByMd5(FILE_MD5)).thenReturn(Optional.of(meta));
        when(fileMetaService.addReference("existing-file-id", 1L, "test.txt")).thenReturn(true);

        StorageStrategy storage = mock(StorageStrategy.class);
        when(storageFactory.current()).thenReturn(storage);
        when(storage.presignDownload(anyString(), anyString(), any(Duration.class))).thenReturn("http://url");

        InitUploadResponse resp = uploadService.init(request, 1L);

        assertThat(resp.getMode()).isEqualTo(UploadMode.INSTANT);
        verify(fileMetaService).incRefCount("existing-file-id");
    }

    @Test
    void init_instantUpload_whenUserAlreadyHasFile_doesNotChargeAgain() {
        when(redisLock.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        FileMeta meta = new FileMeta();
        meta.setFileId("existing-file-id");
        meta.setFileSize(10 * 1024 * 1024L);
        meta.setBucket("bucket");
        meta.setObjectKey("key");
        meta.setOwnerId(1L);
        when(fileMetaService.findReusableByMd5(FILE_MD5)).thenReturn(Optional.of(meta));
        when(fileMetaService.canAccess(meta, 1L)).thenReturn(true);
        when(fileMetaService.addReference("existing-file-id", 1L, "test.txt")).thenReturn(false);

        StorageStrategy storage = mock(StorageStrategy.class);
        when(storageFactory.current()).thenReturn(storage);
        when(storage.presignDownload(anyString(), anyString(), any(Duration.class))).thenReturn("http://url");

        InitUploadResponse resp = uploadService.init(request, 1L);

        assertThat(resp.getMode()).isEqualTo(UploadMode.INSTANT);
        verify(quotaService, never()).checkCapacityOrThrow(anyLong(), anyLong());
        verify(fileMetaService, never()).incRefCount(anyString());
        verify(quotaService, never()).addUsed(anyLong(), anyLong());
    }

    @Test
    void init_instantUpload_whenLocalStorage_doesNotRequirePresignedUrl() {
        when(redisLock.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        FileMeta meta = new FileMeta();
        meta.setFileId("existing-file-id");
        meta.setFileSize(10 * 1024 * 1024L);
        meta.setBucket("bucket");
        meta.setObjectKey("key");
        when(fileMetaService.findReusableByMd5(FILE_MD5)).thenReturn(Optional.of(meta));
        when(fileMetaService.addReference("existing-file-id", 1L, "test.txt")).thenReturn(true);

        StorageStrategy storage = mock(StorageStrategy.class);
        when(storageFactory.current()).thenReturn(storage);
        when(storage.type()).thenReturn("local");

        InitUploadResponse resp = uploadService.init(request, 1L);

        assertThat(resp.getMode()).isEqualTo(UploadMode.INSTANT);
        assertThat(resp.getUrl()).isEmpty();
        verify(storage, never()).presignDownload(anyString(), anyString(), any(Duration.class));
        verify(fileMetaService).incRefCount("existing-file-id");
    }

    @Test
    void init_resume_whenSessionRunning() {
        when(redisLock.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(fileMetaService.findReusableByMd5(anyString())).thenReturn(Optional.empty());

        UploadSession existing = new UploadSession();
        existing.setFileId(FILE_ID);
        existing.setFileMd5(FILE_MD5);
        existing.setStatus(UploadSessionStatus.RUNNING);
        existing.setChunkSize(5 * 1024 * 1024);
        existing.setChunkTotal(10);
        existing.setExpireAt(LocalDateTime.now().plusHours(12));
        when(sessionMapper.selectOne(any())).thenReturn(existing);

        when(progress.uploaded(FILE_ID)).thenReturn(List.of(0, 1, 2));

        InitUploadResponse resp = uploadService.init(request, 1L);

        assertThat(resp.getMode()).isEqualTo(UploadMode.RESUME);
        verify(fileMetaService).addReference(FILE_ID, 1L, "test.txt");
        assertThat(resp.getUploaded()).containsExactly(0, 1, 2);
        assertThat(resp.getMissing()).contains(3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void init_newUpload_whenNoMatch() {
        when(redisLock.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(fileMetaService.findReusableByMd5(anyString())).thenReturn(Optional.empty());
        when(sessionMapper.selectOne(any())).thenReturn(null);

        StorageStrategy storage = mock(StorageStrategy.class);
        lenient().when(storageFactory.current()).thenReturn(storage);
        lenient().when(storageFactory.defaultBucket()).thenReturn("default-bucket");

        InitUploadResponse resp = uploadService.init(request, 1L);

        assertThat(resp.getMode()).isEqualTo(UploadMode.UPLOAD);
        verify(sessionMapper).insert(any(UploadSession.class));
        verify(fileMetaService).addReference(anyString(), eq(1L), eq("test.txt"));
        verify(progress).init(anyString(), eq(2), eq(Duration.ofHours(24)));
    }

    @Test
    void init_throwsWhenLockFailed() {
        when(redisLock.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> uploadService.init(request, 1L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UPLOAD_IN_PROGRESS));
    }

    // ==================== uploadChunk tests ====================

    @Test
    void uploadChunk_idempotent_whenAlreadyDone() throws Exception {
        UploadSession session = buildRunningSession(FILE_ID, 10);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(fileMetaService.hasReference(FILE_ID, 1L)).thenReturn(true);
        when(progress.isDone(FILE_ID, 3)).thenReturn(true);
        when(progress.doneCount(FILE_ID)).thenReturn(5);

        StorageStrategy storage = mock(StorageStrategy.class);
        lenient().when(storageFactory.current()).thenReturn(storage);

        ChunkUploadResponse resp = uploadService.uploadChunk(
                FILE_ID, 3, CHUNK_MD5, 1L, 1024, new ByteArrayInputStream(new byte[0]));

        verify(storage, never()).put(any(PutRequest.class));
        assertThat(resp).isNotNull();
    }

    @Test
    void uploadChunk_md5Mismatch_deletesAndThrows() throws Exception {
        UploadSession session = buildRunningSession(FILE_ID, 10);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(fileMetaService.hasReference(FILE_ID, 1L)).thenReturn(true);
        when(progress.isDone(FILE_ID, 0)).thenReturn(false);

        StorageStrategy storage = mock(StorageStrategy.class);
        when(storageFactory.current()).thenReturn(storage);
        when(storage.put(any(PutRequest.class))).thenReturn(new PutResult("part-key", "etag1", 11L));

        // ioExecutor.execute should capture the runnable
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(ioExecutor).execute(any(Runnable.class));

        byte[] content = "hello world".getBytes();
        InputStream data = new ByteArrayInputStream(content);

        assertThatThrownBy(() -> uploadService.uploadChunk(
                FILE_ID, 0, WRONG_MD5, 1L, content.length, data))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CHUNK_MD5_MISMATCH));

        verify(ioExecutor).execute(any(Runnable.class));
    }

    // ==================== merge tests ====================

    @Test
    void merge_throwsWhenNotAllChunksDone() {
        UploadSession session = buildRunningSession(FILE_ID, 10);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(fileMetaService.hasReference(FILE_ID, 1L)).thenReturn(true);
        when(redisLock.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(progress.doneCount(FILE_ID)).thenReturn(7);
        when(progress.uploaded(FILE_ID)).thenReturn(List.of(0, 1, 2, 3, 4, 5, 6));

        assertThatThrownBy(() -> uploadService.merge(FILE_ID, 1L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CHUNK_NOT_COMPLETE));
    }

    @Test
    void merge_success_callsComposeAndChecksum() {
        UploadSession session = buildRunningSession(FILE_ID, 3);
        session.setBucket("bucket");
        session.setObjectKey("final-key");
        session.setFileMd5(FILE_MD5);
        session.setMimeType("application/octet-stream");
        session.setFileSize(1024L);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(fileMetaService.hasReference(FILE_ID, 1L)).thenReturn(true);
        when(redisLock.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(progress.doneCount(FILE_ID)).thenReturn(3);

        StorageStrategy storage = mock(StorageStrategy.class);
        when(storageFactory.current()).thenReturn(storage);
        when(storage.compose(any(ComposeRequest.class)))
                .thenReturn(new ComposeResult("final-key", "merged-etag", 1024L));
        when(storage.type()).thenReturn("minio");

        var result = uploadService.merge(FILE_ID, 1L);

        assertThat(result).isNotNull();
        verify(storage).compose(any(ComposeRequest.class));
        verify(mergeTx).finalizeSuccess(eq(session), eq("minio"));
        verify(checksumProducer).publish(any());
    }

    // ==================== cancel tests ====================

    @Test
    void cancel_removesOnlyCurrentParticipant_whenOthersRemain() {
        UploadSession session = buildRunningSession(FILE_ID, 3);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(fileMetaService.hasReference(FILE_ID, 2L)).thenReturn(true);
        when(fileMetaService.referenceCount(FILE_ID)).thenReturn(1);

        uploadService.cancel(FILE_ID, 2L);

        verify(fileMetaService).removeReference(FILE_ID, 2L);
        verify(sessionMapper, never()).update(any(), any());
        verify(progress, never()).clear(anyString());
        verify(cleanupExecutor, never()).execute(any(Runnable.class));
    }

    // ==================== helpers ====================

    private UploadSession buildRunningSession(String fileId, int chunkTotal) {
        UploadSession s = new UploadSession();
        s.setFileId(fileId);
        s.setStatus(UploadSessionStatus.RUNNING);
        s.setChunkTotal(chunkTotal);
        s.setChunkSize(5 * 1024 * 1024);
        s.setBucket("test-bucket");
        s.setExpireAt(LocalDateTime.now().plusHours(12));
        return s;
    }
}
