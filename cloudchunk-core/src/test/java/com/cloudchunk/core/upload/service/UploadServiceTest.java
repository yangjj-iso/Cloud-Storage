package com.cloudchunk.core.upload.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cloudchunk.common.concurrent.BoundedVirtualThreadExecutor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
import com.cloudchunk.storage.StorageStrategy;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.storage.model.ComposeRequest;
import com.cloudchunk.storage.model.ComposeResult;
import com.cloudchunk.storage.model.GetRequest;
import com.cloudchunk.storage.model.ObjectStat;
import com.cloudchunk.storage.model.PutRequest;
import com.cloudchunk.storage.model.PutResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    private static final String FILE_ID = "11111111111111111111111111111111";
    private static final String FILE_MD5 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CHUNK_MD5 = "5eb63bbbe01eeed093cb22bb8f5acdc3";
    private static final String WRONG_MD5 = "00000000000000000000000000000000";
    /** 一个合法但与 CHUNK_MD5 不同的内容 MD5，用来模拟直传上去的是另一份内容。 */
    private static final String OTHER_CONTENT_MD5 = "acbd18db4cc2f85cedef654fccc4a4d8";
    private static final int CHUNK_SIZE = 5 * 1024 * 1024;

    @Mock private UploadSessionMapper sessionMapper;
    @Mock private ChunkRecordMapper chunkMapper;
    @Mock private FileMetaService fileMetaService;
    @Mock private QuotaService quotaService;
    @Mock private StorageStrategyFactory storageFactory;
    @Mock private ProgressStore progress;
    @Mock private RedisService redis;
    @Mock private RedisLock redisLock;
    @Mock private CloudchunkProperties properties;
    @Mock private MergeTransactionService mergeTx;
    @Mock private UserFileService userFileService;
    @Mock private BoundedVirtualThreadExecutor ioExecutor;
    @Mock private BoundedVirtualThreadExecutor cleanupExecutor;
    @Mock private TransactionTemplate transactionTemplate;

    private UploadService uploadService;

    private InitUploadRequest request;
    private CloudchunkProperties.Chunk chunkProps;
    private CloudchunkProperties.Upload uploadProps;

    /** 单测没有 MyBatis 上下文，手动初始化实体元数据才能读取 Wrapper 生成的 SQL 片段。 */
    @BeforeAll
    static void initMybatisLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.cloudchunk.core.upload.entity.ChunkRecord.class);
    }

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
                properties,
                mergeTx,
                userFileService,
                ioExecutor,
                cleanupExecutor,
                transactionTemplate);

        lenient().when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0))
                        .doInTransaction(null));

        chunkProps = new CloudchunkProperties.Chunk();
        chunkProps.setMinSize(1024 * 1024);
        chunkProps.setMaxSize(100 * 1024 * 1024);
        chunkProps.setSessionTtl(Duration.ofHours(24));
        lenient().when(properties.getChunk()).thenReturn(chunkProps);
        uploadProps = new CloudchunkProperties.Upload();
        uploadProps.setCrossUserDedupEnabled(true);
        lenient().when(properties.getUpload()).thenReturn(uploadProps);

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
        when(fileMetaService.addReference(anyString(), eq(1L), eq("test.txt"))).thenReturn(true);

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
    void init_doesNotGrantCrossUserDedupFromHashByDefault() {
        uploadProps.setCrossUserDedupEnabled(false);
        when(redisLock.tryLock(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        FileMeta otherUsersFile = new FileMeta();
        otherUsersFile.setFileId("existing-file-id");
        otherUsersFile.setOwnerId(2L);
        otherUsersFile.setFileSize(request.getFileSize());
        when(fileMetaService.findReusableByMd5(FILE_MD5)).thenReturn(Optional.of(otherUsersFile));
        when(fileMetaService.canAccess(otherUsersFile, 1L)).thenReturn(false);
        when(sessionMapper.selectOne(any())).thenReturn(null);
        when(fileMetaService.addReference(anyString(), eq(1L), eq("test.txt"))).thenReturn(true);

        InitUploadResponse resp = uploadService.init(request, 1L);

        assertThat(resp.getMode()).isEqualTo(UploadMode.UPLOAD);
        verify(quotaService).tryReserve(1L, request.getFileSize());
        verify(fileMetaService, never()).incRefCount("existing-file-id");
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

    // ==================== confirmChunk (presigned direct upload) tests ====================

    @Test
    void confirmChunk_acceptsWhenEtagMatchesDeclaredMd5_withoutReadingObjectBack() {
        StorageStrategy storage = stubConfirmSession();
        when(storage.stat("test-bucket", partKey(0)))
                .thenReturn(objectStat(CHUNK_SIZE, CHUNK_MD5));

        ChunkUploadResponse resp = uploadService.confirmChunk(FILE_ID, 0, CHUNK_MD5, 1L);

        assertThat(resp).isNotNull();
        // MinIO 对单次 PUT 的 ETag 就是内容 MD5，这条路径不应该产生回源读取。
        verify(storage, never()).get(any(GetRequest.class));
        verify(chunkMapper).upsert(argThat(rec -> CHUNK_MD5.equals(rec.getChunkMd5())));
    }

    @Test
    void confirmChunk_rejectsWhenObjectContentDoesNotMatchDeclaredMd5() {
        StorageStrategy storage = stubConfirmSession();
        when(storage.stat("test-bucket", partKey(0)))
                .thenReturn(objectStat(CHUNK_SIZE, OTHER_CONTENT_MD5));
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(ioExecutor).execute(any(Runnable.class));

        assertThatThrownBy(() -> uploadService.confirmChunk(FILE_ID, 0, CHUNK_MD5, 1L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CHUNK_MD5_MISMATCH));

        verify(storage).delete("test-bucket", partKey(0));
        verify(chunkMapper, never()).upsert(any());
        verify(progress, never()).markDone(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    void confirmChunk_readsObjectBackWhenEtagIsNotAContentMd5() {
        StorageStrategy storage = stubConfirmSession();
        // 分段上传的 ETag 形如 <hex>-<parts>，不能当内容 MD5 用。
        when(storage.stat("test-bucket", partKey(0)))
                .thenReturn(objectStat(CHUNK_SIZE, OTHER_CONTENT_MD5 + "-2"));
        when(storage.get(any(GetRequest.class)))
                .thenReturn(new ByteArrayInputStream("hello world".getBytes()));

        ChunkUploadResponse resp = uploadService.confirmChunk(FILE_ID, 0, CHUNK_MD5, 1L);

        assertThat(resp).isNotNull();
        verify(storage).get(any(GetRequest.class));
        verify(chunkMapper).upsert(argThat(rec -> CHUNK_MD5.equals(rec.getChunkMd5())));
    }

    @Test
    void confirmChunk_requiresDeclaredMd5WhenVerificationIsOn() {
        StorageStrategy storage = stubConfirmSession();
        when(storage.stat("test-bucket", partKey(0)))
                .thenReturn(objectStat(CHUNK_SIZE, CHUNK_MD5));

        assertThatThrownBy(() -> uploadService.confirmChunk(FILE_ID, 0, "", 1L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAMETER));

        verify(chunkMapper, never()).upsert(any());
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
    }

    @Test
    void merge_success_removesChunkRecordsAlongWithPartObjects() {
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
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(cleanupExecutor).execute(any(Runnable.class));

        uploadService.merge(FILE_ID, 1L);

        // 分片对象和描述它们的 chunk_record 必须一起消失，否则库里留下指向空对象的死指针。
        verify(storage).deleteBatch(eq("bucket"), anyList());
        verify(chunkMapper).delete(any());
    }

    @Test
    void dedupChunks_onlyConsidersChunksFromLiveSessions() {
        UploadSession target = buildRunningSession(FILE_ID, 1);
        target.setFileSize(1024L);
        when(sessionMapper.selectOne(any())).thenReturn(target);
        when(fileMetaService.hasReference(FILE_ID, 1L)).thenReturn(true);
        when(progress.isDone(FILE_ID, 0)).thenReturn(false);
        when(chunkMapper.selectOne(any())).thenReturn(null);
        StorageStrategy storage = mock(StorageStrategy.class);
        lenient().when(storageFactory.current()).thenReturn(storage);

        uploadService.dedupChunks(FILE_ID, Map.of(0, CHUNK_MD5), 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<com.cloudchunk.core.upload.entity.ChunkRecord>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(chunkMapper).selectOne(captor.capture());
        // 已合并会话的分片对象早已被删除，把它们纳入候选会让 copy 必然失败。
        assertThat(captor.getValue().getSqlSegment())
                .contains("upload_session")
                .contains("expire_at");
    }

    // ==================== cancel tests ====================

    @Test
    void cancel_removesOnlyCurrentParticipant_whenOthersRemain() {
        UploadSession session = buildRunningSession(FILE_ID, 3);
        session.setFileSize(1024L);
        when(sessionMapper.selectByFileIdForUpdate(FILE_ID)).thenReturn(session);
        when(fileMetaService.hasReference(FILE_ID, 2L)).thenReturn(true);
        when(fileMetaService.removeReference(FILE_ID, 2L)).thenReturn(true);
        when(fileMetaService.referenceCount(FILE_ID)).thenReturn(1);

        uploadService.cancel(FILE_ID, 2L);

        verify(fileMetaService).removeReference(FILE_ID, 2L);
        verify(quotaService).releaseReservation(2L, 1024L);
        verify(sessionMapper, never()).update(any(), any());
        verify(progress, never()).clear(anyString());
        verify(cleanupExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void cancel_doesNotReleaseQuotaWhenConcurrentRemovalAlreadyWon() {
        UploadSession session = buildRunningSession(FILE_ID, 3);
        session.setFileSize(1024L);
        when(sessionMapper.selectByFileIdForUpdate(FILE_ID)).thenReturn(session);
        when(fileMetaService.hasReference(FILE_ID, 2L)).thenReturn(true);
        when(fileMetaService.removeReference(FILE_ID, 2L)).thenReturn(false);

        uploadService.cancel(FILE_ID, 2L);

        verify(quotaService, never()).releaseReservation(anyLong(), anyLong());
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void dedupChunks_doesNotReuseAnotherUsersChunkWhenCrossUserDedupIsDisabled() {
        uploadProps.setCrossUserDedupEnabled(false);
        UploadSession target = buildRunningSession(FILE_ID, 1);
        UploadSession source = buildRunningSession("22222222222222222222222222222222", 1);
        source.setOwnerId(2L);
        when(sessionMapper.selectOne(any())).thenReturn(target, source);
        when(fileMetaService.hasReference(FILE_ID, 1L)).thenReturn(true);
        when(fileMetaService.hasReference(source.getFileId(), 1L)).thenReturn(false);
        when(progress.isDone(FILE_ID, 0)).thenReturn(false);

        var existing = new com.cloudchunk.core.upload.entity.ChunkRecord();
        existing.setFileId(source.getFileId());
        existing.setChunkIndex(0);
        existing.setChunkMd5(CHUNK_MD5);
        existing.setChunkSize(1024);
        when(chunkMapper.selectOne(any())).thenReturn(existing);
        StorageStrategy storage = mock(StorageStrategy.class);
        when(storageFactory.current()).thenReturn(storage);

        assertThat(uploadService.dedupChunks(FILE_ID, Map.of(0, CHUNK_MD5), 1L)).isEmpty();
        verify(storage, never()).copy(anyString(), anyString(), anyString(), anyString());
    }

    // ==================== helpers ====================

    /** 准备一个可以直接调 confirmChunk 的 RUNNING 会话，返回被 mock 的存储策略。 */
    private StorageStrategy stubConfirmSession() {
        UploadSession session = buildRunningSession(FILE_ID, 3);
        session.setFileSize(CHUNK_SIZE * 3L);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(fileMetaService.hasReference(FILE_ID, 1L)).thenReturn(true);
        when(progress.isDone(FILE_ID, 0)).thenReturn(false);
        StorageStrategy storage = mock(StorageStrategy.class);
        when(storageFactory.current()).thenReturn(storage);
        return storage;
    }

    private static String partKey(int index) {
        return com.cloudchunk.common.util.ObjectKeyUtils.partKey(FILE_ID, index);
    }

    private static ObjectStat objectStat(long size, String etag) {
        return new ObjectStat("test-bucket", partKey(0), size, etag, Instant.now(),
                "application/octet-stream", Map.of());
    }

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
