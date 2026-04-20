package com.cloudchunk.core.upload.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudchunk.common.concurrent.BoundedVirtualThreadExecutor;
import com.cloudchunk.common.constant.RedisKeys;
import com.cloudchunk.common.enums.ChunkStatus;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.enums.UploadSessionStatus;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.util.IdUtils;
import com.cloudchunk.common.util.Md5Utils;
import com.cloudchunk.common.util.MimeUtils;
import com.cloudchunk.common.util.ObjectKeyUtils;
import com.cloudchunk.core.CloudchunkProperties;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.quota.service.QuotaService;
import com.cloudchunk.core.upload.dto.ChunkUploadResponse;
import com.cloudchunk.core.upload.dto.InitUploadRequest;
import com.cloudchunk.core.upload.dto.InitUploadResponse;
import com.cloudchunk.core.upload.dto.MergeResult;
import com.cloudchunk.core.upload.dto.UploadProgress;
import com.cloudchunk.core.upload.entity.ChunkRecord;
import com.cloudchunk.core.upload.entity.UploadSession;
import com.cloudchunk.core.upload.mapper.ChunkRecordMapper;
import com.cloudchunk.core.upload.mapper.UploadSessionMapper;
import com.cloudchunk.infra.redis.RedisLock;
import com.cloudchunk.infra.redis.RedisService;
import com.cloudchunk.mq.message.ChecksumMessage;
import com.cloudchunk.mq.producer.ChecksumProducer;
import com.cloudchunk.storage.StorageStrategy;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.storage.model.ComposeRequest;
import com.cloudchunk.storage.model.ComposeResult;
import com.cloudchunk.storage.model.PutRequest;
import com.cloudchunk.storage.model.PutResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudchunk.storage.model.ObjectStat;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final UploadSessionMapper sessionMapper;
    private final ChunkRecordMapper chunkMapper;
    private final FileMetaService fileMetaService;
    private final QuotaService quotaService;
    private final StorageStrategyFactory storageFactory;
    private final ProgressStore progress;
    private final RedisService redis;
    private final RedisLock redisLock;
    private final ChecksumProducer checksumProducer;
    private final CloudchunkProperties properties;
    private final MergeTransactionService mergeTx;
    private final BoundedVirtualThreadExecutor ioExecutor;
    private final BoundedVirtualThreadExecutor cleanupExecutor;

    public UploadService(UploadSessionMapper sessionMapper,
                         ChunkRecordMapper chunkMapper,
                         FileMetaService fileMetaService,
                         QuotaService quotaService,
                         StorageStrategyFactory storageFactory,
                         ProgressStore progress,
                         RedisService redis,
                         RedisLock redisLock,
                         ChecksumProducer checksumProducer,
                         CloudchunkProperties properties,
                         MergeTransactionService mergeTx,
                         BoundedVirtualThreadExecutor ioExecutor,
                         BoundedVirtualThreadExecutor cleanupExecutor) {
        this.sessionMapper = sessionMapper;
        this.chunkMapper = chunkMapper;
        this.fileMetaService = fileMetaService;
        this.quotaService = quotaService;
        this.storageFactory = storageFactory;
        this.progress = progress;
        this.redis = redis;
        this.redisLock = redisLock;
        this.checksumProducer = checksumProducer;
        this.properties = properties;
        this.mergeTx = mergeTx;
        this.ioExecutor = ioExecutor;
        this.cleanupExecutor = cleanupExecutor;
    }

    /* ========================================================= init ========================================================= */

    public InitUploadResponse init(InitUploadRequest req, long userId) {
        validateInit(req);
        quotaService.checkCapacityOrThrow(userId, req.getFileSize());

        String lockKey = RedisKeys.uploadInstantLock(req.getFileMd5());
        String token = IdUtils.uuid32();
        if (!redisLock.tryLock(lockKey, token, Duration.ofMinutes(10))) {
            throw BizException.of(ErrorCode.UPLOAD_IN_PROGRESS, req.getFileMd5());
        }
        try {
            // 1. 秒传命中
            var instant = fileMetaService.findAvailableByMd5(req.getFileMd5());
            if (instant.isPresent()) {
                FileMeta m = instant.get();
                fileMetaService.incRefCount(m.getFileId());
                String url = storageFactory.current()
                        .presignDownload(m.getBucket(), m.getObjectKey(), properties.getChunk().getSessionTtl());
                return InitUploadResponse.instant(m.getFileId(), url);
            }

            // 2. 存在进行中会话 -> 续传
            UploadSession existing = sessionMapper.selectOne(new LambdaQueryWrapper<UploadSession>()
                    .eq(UploadSession::getFileMd5, req.getFileMd5())
                    .eq(UploadSession::getStatus, UploadSessionStatus.RUNNING)
                    .last("limit 1"));
            if (existing != null && existing.getExpireAt().isAfter(LocalDateTime.now())) {
                List<Integer> uploaded = loadOrRebuildUploaded(existing);
                List<Integer> missing = buildMissing(existing.getChunkTotal(), uploaded);
                return InitUploadResponse.resume(existing.getFileId(), existing.getChunkSize(),
                        existing.getChunkTotal(), uploaded, missing, existing.getExpireAt());
            }

            // 3. 全新上传
            UploadSession s = createSession(req, userId);
            progress.init(s.getFileId(), s.getChunkTotal(), properties.getChunk().getSessionTtl());
            return InitUploadResponse.upload(s.getFileId(), s.getChunkSize(), s.getChunkTotal(), s.getExpireAt());
        } finally {
            redisLock.unlock(lockKey, token);
        }
    }

    private void validateInit(InitUploadRequest req) {
        int min = properties.getChunk().getMinSize();
        int max = properties.getChunk().getMaxSize();
        if (req.getChunkSize() < min || req.getChunkSize() > max) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "chunkSize out of range");
        }
        long expected = (long) req.getChunkSize() * (req.getChunkTotal() - 1);
        if (expected >= req.getFileSize() || expected + req.getChunkSize() < req.getFileSize()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER,
                    "chunkTotal does not match fileSize/chunkSize");
        }
    }

    private UploadSession createSession(InitUploadRequest req, long userId) {
        String fileId = IdUtils.uuid32();
        UploadSession s = new UploadSession();
        s.setFileId(fileId);
        s.setFileMd5(req.getFileMd5());
        s.setFileName(req.getFileName());
        s.setFileSize(req.getFileSize());
        s.setMimeType(req.getMimeType() == null ? MimeUtils.OCTET_STREAM : req.getMimeType());
        s.setChunkSize(req.getChunkSize());
        s.setChunkTotal(req.getChunkTotal());
        s.setOwnerId(userId);
        s.setStatus(UploadSessionStatus.RUNNING);
        s.setBucket(storageFactory.defaultBucket());
        s.setObjectKey(ObjectKeyUtils.finalKey(fileId, req.getFileName()));
        s.setExpireAt(LocalDateTime.now().plus(properties.getChunk().getSessionTtl()));
        sessionMapper.insert(s);
        log.info("upload session created: fileId={}, total={}, size={}, user={}",
                fileId, req.getChunkTotal(), req.getFileSize(), userId);
        return s;
    }

    /* ========================================================= chunk ========================================================= */

    /**
     * 分片上传（高并发路径，不用 @Transactional）。
     *
     * 为什么去 @Transactional：
     * <ol>
     *   <li>方法内包含 MinIO PUT（~秒级网络 IO），若裹在 DB 事务中会长期占用 HikariCP 连接，
     *       并发 100 请求就能打爆 30 大小的连接池（连接 = QPS × 平均耗时，Little's Law）。</li>
     *   <li>两处 DB 写（ChunkRecord upsert、ProgressStore Lua）本身都是幂等原子操作，
     *       不依赖跨操作一致性。失败通过幂等重试 & progress 重建兜底。</li>
     * </ol>
     */
    public ChunkUploadResponse uploadChunk(String fileId, int chunkIndex, String chunkMd5,
                                           long chunkSize, InputStream data) throws IOException {
        UploadSession s = requireRunningSession(fileId);
        if (chunkIndex < 0 || chunkIndex >= s.getChunkTotal()) {
            throw BizException.of(ErrorCode.CHUNK_INDEX_INVALID);
        }

        // 已完成幂等命中
        if (progress.isDone(fileId, chunkIndex)) {
            ChunkRecord exist = chunkMapper.selectOne(new LambdaQueryWrapper<ChunkRecord>()
                    .eq(ChunkRecord::getFileId, fileId).eq(ChunkRecord::getChunkIndex, chunkIndex));
            String etag = exist == null ? null : exist.getEtag();
            return new ChunkUploadResponse(fileId, chunkIndex, etag, 1,
                    progress.doneCount(fileId) == s.getChunkTotal());
        }

        // 流式上传：DigestInputStream 在传输过程中同步计算 MD5，避免 readAllBytes() 的堆内存分配
        StorageStrategy storage = storageFactory.current();
        String partKey = ObjectKeyUtils.partKey(fileId, chunkIndex);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        DigestInputStream dis = new DigestInputStream(data, digest);
        PutResult pr = storage.put(PutRequest.of(s.getBucket(), partKey,
                dis, chunkSize, MimeUtils.OCTET_STREAM));

        String actualMd5 = Md5Utils.hex(digest.digest());
        if (!actualMd5.equalsIgnoreCase(chunkMd5)) {
            // MD5 不匹配：放入有界 IO 线程池异步删除错误分片，避免无限 vthread
            ioExecutor.execute(() -> {
                try { storage.delete(s.getBucket(), partKey); } catch (Exception ignored) {}
            });
            throw BizException.of(ErrorCode.CHUNK_MD5_MISMATCH,
                    "expect=" + chunkMd5 + ", actual=" + actualMd5);
        }

        ChunkRecord rec = new ChunkRecord();
        rec.setFileId(fileId);
        rec.setChunkIndex(chunkIndex);
        rec.setChunkMd5(actualMd5);
        rec.setChunkSize((int) chunkSize);
        rec.setEtag(pr.etag());
        rec.setStatus(ChunkStatus.DONE);
        chunkMapper.upsert(rec);

        // Lua 原子更新进度，直接拿到最新 count（省一次 RTT）
        int done = progress.markDone(fileId, chunkIndex, properties.getChunk().getSessionTtl());
        boolean allReady = done == s.getChunkTotal();
        if (log.isDebugEnabled()) {
            log.debug("chunk uploaded: fileId={}, idx={}, etag={}, done={}/{}",
                    fileId, chunkIndex, pr.etag(), done, s.getChunkTotal());
        }

        return new ChunkUploadResponse(fileId, chunkIndex, pr.etag(), 1, allReady);
    }

    /* ====================================================== presigned PUT ====================================================== */

    /**
     * 为指定分片批量生成 MinIO presigned PUT URL，前端可直传 MinIO 跳过后端。
     */
    public Map<Integer, String> presignChunks(String fileId, List<Integer> indices) {
        UploadSession s = requireRunningSession(fileId);
        StorageStrategy storage = storageFactory.current();
        Duration ttl = properties.getChunk().getSessionTtl();
        Map<Integer, String> urls = new LinkedHashMap<>(indices.size());
        for (int idx : indices) {
            if (idx < 0 || idx >= s.getChunkTotal()) continue;
            String key = ObjectKeyUtils.partKey(fileId, idx);
            urls.put(idx, storage.presignUpload(s.getBucket(), key, ttl));
        }
        return urls;
    }

    /**
     * 前端直传 MinIO 完成后回调确认：校验对象存在 → 记录 ChunkRecord → 更新进度。
     * 数据路径完全不经过后端，后端只做元数据管理。
     */
    public ChunkUploadResponse confirmChunk(String fileId, int chunkIndex, String chunkMd5) {
        UploadSession s = requireRunningSession(fileId);
        if (chunkIndex < 0 || chunkIndex >= s.getChunkTotal()) {
            throw BizException.of(ErrorCode.CHUNK_INDEX_INVALID);
        }
        if (progress.isDone(fileId, chunkIndex)) {
            return new ChunkUploadResponse(fileId, chunkIndex, null, 1,
                    progress.doneCount(fileId) == s.getChunkTotal());
        }
        StorageStrategy storage = storageFactory.current();
        String partKey = ObjectKeyUtils.partKey(fileId, chunkIndex);
        ObjectStat stat;
        try {
            stat = storage.stat(s.getBucket(), partKey);
        } catch (Exception e) {
            throw BizException.of(ErrorCode.NOT_FOUND, "chunk not in storage: " + chunkIndex);
        }

        ChunkRecord rec = new ChunkRecord();
        rec.setFileId(fileId);
        rec.setChunkIndex(chunkIndex);
        rec.setChunkMd5(chunkMd5 != null ? chunkMd5 : "");
        rec.setChunkSize((int) stat.size());
        rec.setEtag(stat.etag());
        rec.setStatus(ChunkStatus.DONE);
        chunkMapper.upsert(rec);

        int done = progress.markDone(fileId, chunkIndex, properties.getChunk().getSessionTtl());
        boolean allReady = done == s.getChunkTotal();
        if (log.isDebugEnabled()) {
            log.debug("chunk confirmed (direct): fileId={}, idx={}, etag={}, done={}/{}",
                    fileId, chunkIndex, stat.etag(), done, s.getChunkTotal());
        }
        return new ChunkUploadResponse(fileId, chunkIndex, stat.etag(), 1, allReady);
    }

    /* ======================================================= chunk dedup ======================================================= */

    /**
     * 分片级去重：前端传入 {chunkIndex → chunkMd5}，后端查找已有相同 MD5 的分片，
     * 通过 MinIO 服务端 CopyObject 零网络开销复制，返回去重成功的 index 列表。
     * <p>
     * 面试关键词：content-addressable dedup、server-side copy、类 rsync 策略。
     */
    public List<Integer> dedupChunks(String fileId, Map<Integer, String> chunkMd5Map) {
        UploadSession s = requireRunningSession(fileId);
        StorageStrategy storage = storageFactory.current();
        List<Integer> deduped = new ArrayList<>();

        for (Map.Entry<Integer, String> entry : chunkMd5Map.entrySet()) {
            int idx = entry.getKey();
            String md5 = entry.getValue();
            if (idx < 0 || idx >= s.getChunkTotal()) continue;

            // 已完成直接跳过
            if (progress.isDone(fileId, idx)) {
                deduped.add(idx);
                continue;
            }

            // 查找其他会话中相同 MD5 的已完成分片
            ChunkRecord existing = chunkMapper.selectOne(new LambdaQueryWrapper<ChunkRecord>()
                    .eq(ChunkRecord::getChunkMd5, md5)
                    .eq(ChunkRecord::getStatus, ChunkStatus.DONE)
                    .ne(ChunkRecord::getFileId, fileId)
                    .last("limit 1"));
            if (existing == null) continue;

            // 服务端拷贝：从源分片 key 复制到当前会话的 part key
            String srcKey = ObjectKeyUtils.partKey(existing.getFileId(), existing.getChunkIndex());
            String dstKey = ObjectKeyUtils.partKey(fileId, idx);
            try {
                storage.copy(s.getBucket(), srcKey, s.getBucket(), dstKey);
            } catch (Exception e) {
                log.debug("dedup copy failed: {} → {}", srcKey, dstKey, e);
                continue; // 拷贝失败则该片需正常上传
            }

            ChunkRecord rec = new ChunkRecord();
            rec.setFileId(fileId);
            rec.setChunkIndex(idx);
            rec.setChunkMd5(md5);
            rec.setChunkSize(existing.getChunkSize());
            rec.setEtag(existing.getEtag());
            rec.setStatus(ChunkStatus.DONE);
            chunkMapper.upsert(rec);
            progress.markDone(fileId, idx, properties.getChunk().getSessionTtl());
            deduped.add(idx);
        }
        log.info("dedup result: fileId={}, checked={}, deduped={}", fileId, chunkMd5Map.size(), deduped.size());
        return deduped;
    }

    /* ========================================================= progress ========================================================= */

    public UploadProgress getProgress(String fileId) {
        UploadSession s = requireRunningSession(fileId);
        List<Integer> uploaded = loadOrRebuildUploaded(s);
        return new UploadProgress(fileId, s.getChunkTotal(), uploaded,
                buildMissing(s.getChunkTotal(), uploaded));
    }

    private List<Integer> loadOrRebuildUploaded(UploadSession s) {
        List<Integer> uploaded = progress.uploaded(s.getFileId());
        if (!uploaded.isEmpty()) return uploaded;
        // Redis 丢失：从 MySQL + Storage 重建
        Set<Integer> db = new TreeSet<>();
        chunkMapper.selectList(new LambdaQueryWrapper<ChunkRecord>()
                .eq(ChunkRecord::getFileId, s.getFileId())
                .eq(ChunkRecord::getStatus, ChunkStatus.DONE))
                .forEach(r -> db.add(r.getChunkIndex()));
        if (db.isEmpty()) return List.of();
        Set<Integer> confirmed = new TreeSet<>(db);
        try {
            var list = storageFactory.current().list(s.getBucket(), ObjectKeyUtils.partPrefix(s.getFileId()), 20000);
            Set<Integer> store = new TreeSet<>();
            list.forEach(it -> {
                int idx = ObjectKeyUtils.parsePartIndex(it.objectKey());
                if (idx >= 0) store.add(idx);
            });
            confirmed.retainAll(store);
        } catch (Exception e) {
            log.warn("list storage for rebuild failed: {}", s.getFileId(), e);
        }
        progress.rebuild(s.getFileId(), s.getChunkTotal(), confirmed, properties.getChunk().getSessionTtl());
        return new ArrayList<>(confirmed);
    }

    private List<Integer> buildMissing(int total, List<Integer> uploaded) {
        Set<Integer> done = new TreeSet<>(uploaded);
        List<Integer> miss = new ArrayList<>(total - done.size());
        for (int i = 0; i < total; i++) {
            if (!done.contains(i)) miss.add(i);
        }
        return miss;
    }

    /* ========================================================= merge ========================================================= */

    public MergeResult merge(String fileId) {
        UploadSession s = requireSession(fileId);
        if (s.getStatus() == UploadSessionStatus.COMPLETED) {
            var exists = fileMetaService.findById(fileId).orElse(null);
            if (exists != null) {
                return new MergeResult(fileId, exists.getStatus().name(),
                        exists.getObjectKey(), null);
            }
        }
        if (s.getStatus() != UploadSessionStatus.RUNNING && s.getStatus() != UploadSessionStatus.MERGING) {
            throw BizException.of(ErrorCode.UPLOAD_SESSION_EXPIRED, s.getStatus().name());
        }

        String lockKey = RedisKeys.uploadMergeLock(fileId);
        String token = IdUtils.uuid32();
        if (!redisLock.tryLock(lockKey, token, Duration.ofMinutes(5))) {
            throw BizException.of(ErrorCode.UPLOAD_IN_PROGRESS, "merge");
        }
        try {
            return doMerge(s);
        } finally {
            redisLock.unlock(lockKey, token);
        }
    }

    private MergeResult doMerge(UploadSession s) {
        int doneCount = progress.doneCount(s.getFileId());
        if (doneCount != s.getChunkTotal()) {
            loadOrRebuildUploaded(s);
            doneCount = progress.doneCount(s.getFileId());
        }
        if (doneCount != s.getChunkTotal()) {
            throw BizException.of(ErrorCode.CHUNK_NOT_COMPLETE,
                    doneCount + "/" + s.getChunkTotal());
        }

        mergeTx.markMerging(s.getFileId());

        StorageStrategy storage = storageFactory.current();
        List<String> sources = new ArrayList<>(s.getChunkTotal());
        for (int i = 0; i < s.getChunkTotal(); i++) {
            sources.add(ObjectKeyUtils.partKey(s.getFileId(), i));
        }
        ComposeResult cr = storage.compose(new ComposeRequest(s.getBucket(), s.getObjectKey(), sources));

        // 事务内：插入 file_meta + 更新 session + 配额
        mergeTx.finalizeSuccess(s, storage.type());

        // 投递校验消息 + 异步清理分片
        ChecksumMessage msg = new ChecksumMessage();
        msg.setFileId(s.getFileId());
        msg.setBucket(s.getBucket());
        msg.setObjectKey(s.getObjectKey());
        msg.setExpectMd5(s.getFileMd5());
        msg.setMimeType(s.getMimeType());
        msg.setFileSize(s.getFileSize());
        checksumProducer.publish(msg);

        progress.clear(s.getFileId());
        cleanupPartsAsync(s.getBucket(), sources);

        log.info("merged fileId={}, key={}, etag={}", s.getFileId(), cr.targetKey(), cr.etag());
        return new MergeResult(s.getFileId(), FileStatus.MERGED.name(), cr.targetKey(), cr.etag());
    }

    private void cleanupPartsAsync(String bucket, List<String> partKeys) {
        cleanupExecutor.execute(() -> {
            try {
                storageFactory.current().deleteBatch(bucket, partKeys);
            } catch (Exception e) {
                log.warn("cleanup parts failed: {}", e.getMessage());
            }
        });
    }

    /* ========================================================= cancel ========================================================= */

    @Transactional(rollbackFor = Exception.class)
    public void cancel(String fileId) {
        UploadSession s = sessionMapper.selectOne(new LambdaQueryWrapper<UploadSession>()
                .eq(UploadSession::getFileId, fileId));
        if (s == null) return;
        sessionMapper.update(null, new LambdaUpdateWrapper<UploadSession>()
                .eq(UploadSession::getFileId, fileId)
                .set(UploadSession::getStatus, UploadSessionStatus.FAILED));
        progress.clear(fileId);
        // 异步清理已上传分片
        cleanupPartsAsync(s.getBucket(), listPartsKeys(s));
        log.info("upload cancelled: {}", fileId);
    }

    private List<String> listPartsKeys(UploadSession s) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < s.getChunkTotal(); i++) {
            keys.add(ObjectKeyUtils.partKey(s.getFileId(), i));
        }
        return keys;
    }

    /* ========================================================= helper ========================================================= */

    private UploadSession requireRunningSession(String fileId) {
        UploadSession s = requireSession(fileId);
        if (s.getStatus() != UploadSessionStatus.RUNNING) {
            throw BizException.of(ErrorCode.UPLOAD_SESSION_EXPIRED, s.getStatus().name());
        }
        if (s.getExpireAt() != null && s.getExpireAt().isBefore(LocalDateTime.now())) {
            throw BizException.of(ErrorCode.UPLOAD_SESSION_EXPIRED, "expired");
        }
        return s;
    }

    private UploadSession requireSession(String fileId) {
        UploadSession s = sessionMapper.selectOne(new LambdaQueryWrapper<UploadSession>()
                .eq(UploadSession::getFileId, fileId));
        if (s == null) throw BizException.of(ErrorCode.NOT_FOUND, "upload_session:" + fileId);
        return s;
    }

    public StorageStrategyFactory storageFactory() { return storageFactory; }
}
