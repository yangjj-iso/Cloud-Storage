package com.cloudchunk.core.quota.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.quota.entity.UserQuota;
import com.cloudchunk.core.quota.mapper.UserQuotaMapper;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class QuotaService {

    private final UserQuotaMapper mapper;

    public QuotaService(UserQuotaMapper mapper) {
        this.mapper = mapper;
    }

    public UserQuota getOrDefault(long userId) {
        UserQuota q = mapper.selectById(userId);
        if (q == null) {
            q = new UserQuota();
            q.setUserId(userId);
            q.setTotalBytes(100L * 1024 * 1024 * 1024); // 默认 100GB
            q.setUsedBytes(0L);
            q.setFileCount(0);
            try {
                mapper.insert(q);
            } catch (Exception ignored) {
                // 并发插入冲突忽略
                q = mapper.selectById(userId);
            }
        }
        return q;
    }

    public void checkCapacityOrThrow(long userId, long incrementBytes) {
        UserQuota q = getOrDefault(userId);
        long used = Optional.ofNullable(q.getUsedBytes()).orElse(0L);
        long total = Optional.ofNullable(q.getTotalBytes()).orElse(Long.MAX_VALUE);
        if (used + incrementBytes > total) {
            throw BizException.of(ErrorCode.QUOTA_EXCEEDED,
                    "used=" + used + ", need=" + incrementBytes + ", total=" + total);
        }
    }

    /** 管理端分配总空间（幂等确保配额行存在后更新 total_bytes）。 */
    public void allocateSpace(long userId, long totalBytes) {
        getOrDefault(userId);
        mapper.update(null, new LambdaUpdateWrapper<UserQuota>()
                .eq(UserQuota::getUserId, userId)
                .set(UserQuota::getTotalBytes, totalBytes));
    }

    public void addUsed(long userId, long bytes) {
        mapper.addUsed(userId, bytes);
    }

    public void subUsed(long userId, long bytes) {
        mapper.subUsed(userId, bytes);
    }

    /**
     * 原子预留容量（reservation 模型）：仅当预留后不超过 total_bytes 时才增加
     * used_bytes/file_count，从根本上消除 checkCapacity + addUsed 的“先查后加”
     * TOCTOU 竞态。容量不足抛 {@link ErrorCode#QUOTA_EXCEEDED}。取消/过期时用
     * {@link #subUsed} 释放。
     */
    public void tryConsume(long userId, long bytes) {
        if (bytes <= 0) {
            return;
        }
        getOrDefault(userId); // 确保配额行存在，否则原子 UPDATE 会命中 0 行
        int affected = mapper.tryAddUsed(userId, bytes);
        if (affected <= 0) {
            throw BizException.of(ErrorCode.QUOTA_EXCEEDED, "need=" + bytes);
        }
    }
}
