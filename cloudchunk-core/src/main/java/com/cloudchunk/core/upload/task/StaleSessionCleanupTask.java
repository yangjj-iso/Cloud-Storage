package com.cloudchunk.core.upload.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudchunk.common.enums.UploadSessionStatus;
import com.cloudchunk.common.util.ObjectKeyUtils;
import com.cloudchunk.core.upload.entity.UploadSession;
import com.cloudchunk.core.upload.mapper.UploadSessionMapper;
import com.cloudchunk.core.upload.service.ProgressStore;
import com.cloudchunk.storage.StorageStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    private final UploadSessionMapper sessionMapper;
    private final ProgressStore progressStore;
    private final StorageStrategyFactory storageFactory;

    public StaleSessionCleanupTask(UploadSessionMapper sessionMapper,
                                   ProgressStore progressStore,
                                   StorageStrategyFactory storageFactory) {
        this.sessionMapper = sessionMapper;
        this.progressStore = progressStore;
        this.storageFactory = storageFactory;
    }

    /**
     * 每小时执行一次：找出 status=RUNNING/MERGING 且 expire_at < now 的会话，
     * 逐个清理 Redis 进度 + MinIO 分片 + 标记 DB 状态为 EXPIRED。
     */
    @Scheduled(fixedDelay = 3600_000, initialDelay = 60_000)
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now();
        List<UploadSession> stale = sessionMapper.selectList(
                new LambdaQueryWrapper<UploadSession>()
                        .in(UploadSession::getStatus,
                                UploadSessionStatus.RUNNING, UploadSessionStatus.MERGING)
                        .lt(UploadSession::getExpireAt, now)
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
        progressStore.clear(s.getFileId());

        List<String> partKeys = new ArrayList<>(s.getChunkTotal());
        for (int i = 0; i < s.getChunkTotal(); i++) {
            partKeys.add(ObjectKeyUtils.partKey(s.getFileId(), i));
        }
        try {
            storageFactory.current().deleteBatch(s.getBucket(), partKeys);
        } catch (Exception e) {
            log.warn("delete stale parts failed: fileId={}, reason={}", s.getFileId(), e.getMessage());
        }

        sessionMapper.update(null, new LambdaUpdateWrapper<UploadSession>()
                .eq(UploadSession::getFileId, s.getFileId())
                .in(UploadSession::getStatus,
                        UploadSessionStatus.RUNNING, UploadSessionStatus.MERGING)
                .set(UploadSession::getStatus, UploadSessionStatus.EXPIRED));

        log.info("cleaned stale session: fileId={}, chunks={}", s.getFileId(), s.getChunkTotal());
    }
}
