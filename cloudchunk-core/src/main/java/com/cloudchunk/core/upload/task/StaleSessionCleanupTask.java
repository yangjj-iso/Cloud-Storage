package com.cloudchunk.core.upload.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudchunk.common.enums.UploadSessionStatus;
import com.cloudchunk.common.util.ObjectKeyUtils;
import com.cloudchunk.core.file.entity.FileReference;
import com.cloudchunk.core.file.mapper.FileReferenceMapper;
import com.cloudchunk.core.quota.service.QuotaService;
import com.cloudchunk.core.upload.entity.UploadSession;
import com.cloudchunk.core.upload.mapper.UploadSessionMapper;
import com.cloudchunk.core.upload.service.ProgressStore;
import com.cloudchunk.storage.StorageStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时扫描已过期但未完成的上传会话（僵尸会话），
 * 清理 Redis 进度记录和 MinIO 上的孤立分片对象，防止存储空间泄漏。
 */
@Component
public class StaleSessionCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(StaleSessionCleanupTask.class);

    private static final int BATCH_SIZE = 100;
    private static final Duration MERGING_GRACE = Duration.ofHours(1);

    private final UploadSessionMapper sessionMapper;
    private final FileReferenceMapper referenceMapper;
    private final QuotaService quotaService;
    private final ProgressStore progressStore;
    private final StorageStrategyFactory storageFactory;
    private final TransactionTemplate transactionTemplate;

    public StaleSessionCleanupTask(UploadSessionMapper sessionMapper,
                                   FileReferenceMapper referenceMapper,
                                   QuotaService quotaService,
                                   ProgressStore progressStore,
                                   StorageStrategyFactory storageFactory,
                                   TransactionTemplate transactionTemplate) {
        this.sessionMapper = sessionMapper;
        this.referenceMapper = referenceMapper;
        this.quotaService = quotaService;
        this.progressStore = progressStore;
        this.storageFactory = storageFactory;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 每小时执行一次：找出 status=RUNNING/MERGING 且 expire_at < now 的会话，
     * 逐个清理 Redis 进度 + MinIO 分片 + 标记 DB 状态为 EXPIRED。
     */
    @Scheduled(fixedDelay = 3600_000, initialDelay = 60_000)
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleMergingBefore = now.minus(MERGING_GRACE);
        List<UploadSession> stale = sessionMapper.selectList(
                new LambdaQueryWrapper<UploadSession>()
                        .and(w -> w
                                .and(q -> q.in(UploadSession::getStatus,
                                                UploadSessionStatus.RUNNING, UploadSessionStatus.FAILED)
                                        .lt(UploadSession::getExpireAt, now))
                                .or(q -> q.eq(UploadSession::getStatus, UploadSessionStatus.MERGING)
                                        .lt(UploadSession::getExpireAt, now)
                                        .lt(UploadSession::getUpdatedAt, staleMergingBefore)))
                        .last("limit " + BATCH_SIZE));

        if (stale.isEmpty()) return;
        log.info("stale session cleanup: found {} expired sessions", stale.size());

        int cleaned = 0;
        for (UploadSession s : stale) {
            try {
                cleanOne(s);
                cleaned++;
            } catch (Exception e) {
                log.warn("cleanup failed for fileId={}: {}", s.getFileId(), e.getMessage());
            }
        }
        log.info("stale session cleanup done: {}/{} cleaned", cleaned, stale.size());
    }

    private void cleanOne(UploadSession s) {
        List<String> partKeys = new ArrayList<>(s.getChunkTotal());
        for (int i = 0; i < s.getChunkTotal(); i++) {
            partKeys.add(ObjectKeyUtils.partKey(s.getFileId(), i));
        }
        try {
            storageFactory.current().deleteBatch(s.getBucket(), partKeys);
        } catch (Exception e) {
            log.warn("delete stale parts failed: fileId={}, reason={}", s.getFileId(), e.getMessage());
            throw new IllegalStateException("delete stale parts failed: " + s.getFileId(), e);
        }

        // 原子占用会话：只有本次成功把 RUNNING/MERGING/FAILED -> EXPIRED，才释放配额并删引用，
        // 避免多实例 / 多轮清理对同一会话重复释放（reservation 模型下会造成少计）。
        Integer updated = transactionTemplate.execute(status -> {
            int n = sessionMapper.update(null, new LambdaUpdateWrapper<UploadSession>()
                    .eq(UploadSession::getFileId, s.getFileId())
                    .in(UploadSession::getStatus,
                            UploadSessionStatus.RUNNING, UploadSessionStatus.MERGING,
                            UploadSessionStatus.FAILED)
                    .set(UploadSession::getStatus, UploadSessionStatus.EXPIRED));
            if (n > 0) {
                // reservation 模型：释放该未完成会话所有参与者预留的配额，再删除引用。
                long reserved = s.getFileSize() == null ? 0L : s.getFileSize();
                for (FileReference ref : referenceMapper.selectByFileId(s.getFileId())) {
                    quotaService.subUsed(ref.getUserId(), reserved);
                }
                referenceMapper.deleteByFileId(s.getFileId());
            }
            return n;
        });
        if (updated != null && updated > 0) {
            progressStore.clear(s.getFileId());
        }

        log.info("cleaned stale session: fileId={}, chunks={}", s.getFileId(), s.getChunkTotal());
    }
}
