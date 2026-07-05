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
import com.cloudchunk.core.drive.service.UserFileService;
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
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.regex.Pattern;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final int MAX_BATCH_CHUNKS = 1_000;
    private static final Pattern HEX32 = Pattern.compile("^[a-fA-F0-9]{32}$");

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
    private final UserFileService userFileService;
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
                         UserFileService userFileService,
                         @Qualifier("ioExecutor") BoundedVirtualThreadExecutor ioExecutor,
                         @Qualifier("cleanupExecutor") BoundedVirtualThreadExecutor cleanupExecutor) {
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
        this.userFileService = userFileService;
        this.ioExecutor = ioExecutor;
        this.cleanupExecutor = cleanupExecutor;
    }

    /* ========================================================= init ========================================================= */

    public InitUploadResponse init(InitUploadRequest req, long userId) {
        // init 是上传会话入口，只处理元数据，不处理文件流。
        // 后续所有分片上传、确认、合并都通过这里返回的 fileId 关联同一个 UploadSession。
        validateInit(req);
        // 同一个 fileMd5 的 init 请求用 Redis 锁串行化，避免并发创建多个等价上传会话。
        String lockKey = RedisKeys.uploadInstantLock(req.getFileMd5());
        String token = IdUtils.uuid32();
        if (!redisLock.tryLock(lockKey, token, Duration.ofMinutes(10))) {
            throw BizException.of(ErrorCode.UPLOAD_IN_PROGRESS, req.getFileMd5());
        }
        try {
            // 1. 秒传命中：只有 AVAILABLE（整文件 MD5 已校验通过）可作为复用对象。
            // MERGED 仍处于待校验状态，不能对外下载或复用。
            var instant = fileMetaService.findReusableByMd5(req.getFileMd5());
            if (instant.isPresent()) {
                FileMeta m = instant.get();
                boolean alreadyAccessible = fileMetaService.canAccess(m, userId);
                boolean added = fileMetaService.addReference(m.getFileId(), userId, req.getFileName());
                if (added && !alreadyAccessible) {
                    // reservation 模型：原子预留配额；失败则回滚刚建立的引用，保持一致。
                    try {
                        quotaService.tryConsume(userId, m.getFileSize());
                    } catch (BizException e) {
                        fileMetaService.removeReference(m.getFileId(), userId);
                        throw e;
                    }
                    fileMetaService.incRefCount(m.getFileId());
                }
                // 秒传成功后把文件挂到用户网盘根目录（幂等）。即使引用已存在（例如文件仍在回收站），
                // 也应创建一个活跃条目；配额和 refCount 只在新引用时增加。
                userFileService.createFileEntryIfAbsent(userId, 0L, m.getFileId(),
                        req.getFileName(), m.getFileSize());
                String url = presignInstantDownload(m);
                return InitUploadResponse.instant(m.getFileId(), url);
            }

            // 2. 存在进行中内容会话 -> 加入共享上传/续传
            UploadSession existing = sessionMapper.selectOne(new LambdaQueryWrapper<UploadSession>()
                    .eq(UploadSession::getFileMd5, req.getFileMd5())
                    .eq(UploadSession::getFileSize, req.getFileSize())
                    .eq(UploadSession::getStatus, UploadSessionStatus.RUNNING)
                    .last("limit 1"));
            if (existing != null && existing.getExpireAt().isAfter(LocalDateTime.now())) {
                // 同一份内容复用已有上传会话。即使 B 请求的 chunkSize 不同，也返回已有会话的分片计划，
                // 前端应按 response.chunkSize/response.chunkTotal 重新切片，避免产生重复物理文件。
                boolean alreadyParticipant = fileMetaService.hasReference(existing.getFileId(), userId);
                if (!alreadyParticipant) {
                    // reservation 模型：先挂引用再原子预留；预留失败回滚引用。
                    boolean added = fileMetaService.addReference(existing.getFileId(), userId, req.getFileName());
                    if (added) {
                        try {
                            quotaService.tryConsume(userId, req.getFileSize());
                        } catch (BizException e) {
                            fileMetaService.removeReference(existing.getFileId(), userId);
                            throw e;
                        }
                    }
                }
                List<Integer> uploaded = loadOrRebuildUploaded(existing);
                List<Integer> missing = buildMissing(existing.getChunkTotal(), uploaded);
                return InitUploadResponse.resume(existing.getFileId(), existing.getChunkSize(),
                        existing.getChunkTotal(), uploaded, missing, existing.getExpireAt());
            }

            // 3. 全新上传 —— 准入即原子预留空间（reservation 模型：并发上传不会超配额，
            // 关闭 check-then-add 的 TOCTOU）。取消/过期时释放。
            quotaService.tryConsume(userId, req.getFileSize());
            UploadSession s;
            try {
                s = createSession(req, userId);
            } catch (RuntimeException e) {
                // 会话落库失败：回滚刚预留的配额，避免泄漏。
                quotaService.subUsed(userId, req.getFileSize());
                throw e;
            }
            fileMetaService.addReference(s.getFileId(), userId, req.getFileName());
            progress.init(s.getFileId(), s.getChunkTotal(), properties.getChunk().getSessionTtl());
            return InitUploadResponse.upload(s.getFileId(), s.getChunkSize(), s.getChunkTotal(), s.getExpireAt());
        } finally {
            redisLock.unlock(lockKey, token);
        }
    }

    private void validateInit(InitUploadRequest req) {
        requireValidMd5(req.getFileMd5(), "fileMd5");
        int min = properties.getChunk().getMinSize();
        int max = properties.getChunk().getMaxSize();
        if (req.getChunkSize() < min || req.getChunkSize() > max) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "chunkSize out of range");
        }
        // 前端会提交 chunkTotal，但后端不能直接信任。
        // 这里用 fileSize/chunkSize 反推，保证最后一片允许小于 chunkSize，其余分片必须能覆盖整个文件。
        long expected = (long) req.getChunkSize() * (req.getChunkTotal() - 1);
        if (expected >= req.getFileSize() || expected + req.getChunkSize() < req.getFileSize()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER,
                    "chunkTotal does not match fileSize/chunkSize");
        }
    }

    private UploadSession createSession(InitUploadRequest req, long userId) {
        // UploadSession 是整次上传的主记录。
        // 分片对象先落到 upload/{fileId}/part.NNNNNN，
        // 合并成功后再写成 objectKey 指向的最终对象。
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

    private String presignInstantDownload(FileMeta meta) {
        StorageStrategy storage = storageFactory.current();
        if ("local".equalsIgnoreCase(storage.type())) {
            return "";
        }
        try {
            return storage.presignDownload(meta.getBucket(), meta.getObjectKey(),
                    properties.getChunk().getSessionTtl());
        } catch (UnsupportedOperationException e) {
            log.warn("instant upload presign download unsupported: storage={}, fileId={}",
                    storage.type(), meta.getFileId());
            return "";
        }
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
                                           long userId,
                                           long chunkSize, InputStream data) throws IOException {
        // 后端代理上传入口：Controller 已经把 HTTP 请求体作为 InputStream 传进来。
        // 此方法负责校验会话、写存储、校验 MD5、记录 DB/Redis 进度。
        requireValidFileId(fileId);
        requireValidMd5(chunkMd5, "chunkMd5");
        UploadSession s = requireRunningSessionForUser(fileId, userId);
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
        // partKey 是临时分片对象，不是最终文件对象。
        // 最终对象 key 保存在 UploadSession.objectKey，只有 merge 阶段才会生成。
        String partKey = ObjectKeyUtils.partKey(fileId, chunkIndex);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        DigestInputStream dis = new DigestInputStream(data, digest);
        // storage.put 会持续读取 dis。MinIO SDK 读流时，DigestInputStream 同步更新 MD5 摘要。
        // 因此这里不用提前把分片读进内存，也不用二次读取请求体。
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

        // 存储成功且 MD5 校验通过后，才把分片标记为 DONE。
        // upsert 支持同一分片重试：重复上传最终覆盖为同一个完成状态。
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
    public Map<Integer, String> presignChunks(String fileId, List<Integer> indices, long userId) {
        requireValidFileId(fileId);
        if (indices == null || indices.size() > MAX_BATCH_CHUNKS) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "too many chunk indices");
        }
        UploadSession s = requireRunningSessionForUser(fileId, userId);
        StorageStrategy storage = storageFactory.current();
        Duration ttl = properties.getChunk().getSessionTtl();
        Map<Integer, String> urls = new LinkedHashMap<>(indices.size());
        for (int idx : indices) {
            if (idx < 0 || idx >= s.getChunkTotal()) continue;
            // 预签名 URL 绑定到同一个临时 partKey。
            // 前端 PUT 到这个 URL 后，MinIO 中应该出现该分片对象。
            String key = ObjectKeyUtils.partKey(fileId, idx);
            urls.put(idx, storage.presignUpload(s.getBucket(), key, ttl));
        }
        return urls;
    }

    /**
     * 前端直传 MinIO 完成后回调确认：校验对象存在 → 记录 ChunkRecord → 更新进度。
     * 数据路径完全不经过后端，后端只做元数据管理。
     */
    public ChunkUploadResponse confirmChunk(String fileId, int chunkIndex, String chunkMd5, long userId) {
        requireValidFileId(fileId);
        if (chunkMd5 != null && !chunkMd5.isBlank()) {
            requireValidMd5(chunkMd5, "chunkMd5");
        }
        UploadSession s = requireRunningSessionForUser(fileId, userId);
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
            // 直传确认的可信依据是存储层 stat。
            // 如果前端声称上传成功但对象不存在，这里会拒绝记录进度。
            stat = storage.stat(s.getBucket(), partKey);
        } catch (Exception e) {
            throw BizException.of(ErrorCode.NOT_FOUND, "chunk not in storage: " + chunkIndex);
        }
        long expectedSize = expectedChunkSize(s, chunkIndex);
        if (stat.size() != expectedSize) {
            try {
                storage.delete(s.getBucket(), partKey);
            } catch (Exception ignored) {}
            throw BizException.of(ErrorCode.INVALID_PARAMETER,
                    "chunkSize mismatch: expected=" + expectedSize + ", actual=" + stat.size());
        }

        // confirmChunk 当前只记录前端传来的 chunkMd5，不重新下载对象计算 MD5。
        // 直传路径若要强校验内容，需要额外设计异步校验或客户端携带可信校验头。
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
    public List<Integer> dedupChunks(String fileId, Map<Integer, String> chunkMd5Map, long userId) {
        requireValidFileId(fileId);
        if (chunkMd5Map == null || chunkMd5Map.size() > MAX_BATCH_CHUNKS) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "too many chunks to dedup");
        }
        UploadSession s = requireRunningSessionForUser(fileId, userId);
        StorageStrategy storage = storageFactory.current();
        List<Integer> deduped = new ArrayList<>();

        for (Map.Entry<Integer, String> entry : chunkMd5Map.entrySet()) {
            if (entry.getKey() == null) {
                throw BizException.of(ErrorCode.INVALID_PARAMETER, "chunkIndex is required");
            }
            int idx = entry.getKey();
            String md5 = entry.getValue();
            if (idx < 0 || idx >= s.getChunkTotal()) continue;
            requireValidMd5(md5, "chunkMd5");

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
            long expectedSize = expectedChunkSize(s, idx);
            if (existing.getChunkSize() == null || existing.getChunkSize() != expectedSize) {
                continue;
            }

            // 服务端拷贝：从源分片 key 复制到当前会话的 part key
            String srcKey = ObjectKeyUtils.partKey(existing.getFileId(), existing.getChunkIndex());
            String dstKey = ObjectKeyUtils.partKey(fileId, idx);
            try {
                // copy 在存储服务端完成，不需要把源分片下载到 Java 进程再上传。
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

    public UploadProgress getProgress(String fileId, long userId) {
        requireValidFileId(fileId);
        UploadSession s = requireRunningSessionForUser(fileId, userId);
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

    private long expectedChunkSize(UploadSession s, int chunkIndex) {
        long chunkSize = s.getChunkSize() == null ? 0 : s.getChunkSize();
        if (chunkIndex == s.getChunkTotal() - 1) {
            return s.getFileSize() - chunkSize * chunkIndex;
        }
        return chunkSize;
    }

    /* ========================================================= merge ========================================================= */

    public MergeResult merge(String fileId, long userId) {
        requireValidFileId(fileId);
        UploadSession s = requireSession(fileId);
        requireParticipant(s, userId);
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
        if (s.getExpireAt() != null && s.getExpireAt().isBefore(LocalDateTime.now())) {
            throw BizException.of(ErrorCode.UPLOAD_SESSION_EXPIRED, "expired");
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
        // 合并前以 Redis 进度为快速判断；Redis 丢失时再从 DB + Storage 重建。
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
            // source 顺序就是最终文件字节顺序，必须按 chunkIndex 从小到大排列。
            sources.add(ObjectKeyUtils.partKey(s.getFileId(), i));
        }
        // compose 是服务端合并：MinIO 在对象存储内部把多个 part 对象组合成最终对象。
        // Java 服务只发起合并指令，不把所有分片重新下载到本地拼接。
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
    public void cancel(String fileId, long userId) {
        requireValidFileId(fileId);
        UploadSession s = requireRunningSessionForUser(fileId, userId);
        // reservation 模型：取消未完成的上传时释放该用户在 init/加入会话时预留的配额。
        // 走到这里说明会话处于 RUNNING 且该用户是参与者（持有预留）。
        long reserved = s.getFileSize() == null ? 0L : s.getFileSize();
        quotaService.subUsed(userId, reserved);
        fileMetaService.removeReference(fileId, userId);
        if (fileMetaService.referenceCount(fileId) > 0) {
            log.info("upload participant cancelled: fileId={}, user={}", fileId, userId);
            return;
        }
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
        // 分片上传、预签名、确认都只允许 RUNNING 会话。
        // COMPLETED/MERGING/FAILED/过期会话不能再继续写入分片。
        UploadSession s = requireSession(fileId);
        if (s.getStatus() != UploadSessionStatus.RUNNING) {
            throw BizException.of(ErrorCode.UPLOAD_SESSION_EXPIRED, s.getStatus().name());
        }
        if (s.getExpireAt() != null && s.getExpireAt().isBefore(LocalDateTime.now())) {
            throw BizException.of(ErrorCode.UPLOAD_SESSION_EXPIRED, "expired");
        }
        return s;
    }

    private UploadSession requireRunningSessionForUser(String fileId, long userId) {
        UploadSession s = requireRunningSession(fileId);
        requireParticipant(s, userId);
        return s;
    }

    private void requireParticipant(UploadSession s, long userId) {
        if (!fileMetaService.hasReference(s.getFileId(), userId)) {
            throw BizException.of(ErrorCode.NOT_FOUND, "upload_session:" + s.getFileId());
        }
    }

    private UploadSession requireSession(String fileId) {
        UploadSession s = sessionMapper.selectOne(new LambdaQueryWrapper<UploadSession>()
                .eq(UploadSession::getFileId, fileId));
        if (s == null) throw BizException.of(ErrorCode.NOT_FOUND, "upload_session:" + fileId);
        return s;
    }

    private void requireValidFileId(String fileId) {
        if (fileId == null || !HEX32.matcher(fileId).matches()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid fileId");
        }
    }

    private void requireValidMd5(String md5, String field) {
        if (md5 == null || !HEX32.matcher(md5).matches()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, field + " must be 32 hex characters");
        }
    }

    public StorageStrategyFactory storageFactory() { return storageFactory; }
}
