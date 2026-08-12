package com.cloudchunk.core.quota.service;

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
            q.setReservedBytes(0L);
            q.setReservedFileCount(0);
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
        long reserved = Optional.ofNullable(q.getReservedBytes()).orElse(0L);
        long total = Optional.ofNullable(q.getTotalBytes()).orElse(Long.MAX_VALUE);
        if (used + reserved + incrementBytes > total) {
            throw BizException.of(ErrorCode.QUOTA_EXCEEDED,
                    "used=" + used + ", reserved=" + reserved
                            + ", need=" + incrementBytes + ", total=" + total);
        }
    }

    /** 管理端分配总空间（幂等确保配额行存在后更新 total_bytes）。 */
    public void allocateSpace(long userId, long totalBytes) {
        if (totalBytes < 0) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "totalBytes must be non-negative");
        }
        getOrDefault(userId);
        if (mapper.setTotalAtLeastCommitted(userId, totalBytes) <= 0) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER,
                    "totalBytes cannot be lower than used and reserved bytes");
        }
    }

    public void addUsed(long userId, long bytes) {
        mapper.addUsed(userId, bytes);
    }

    public void subUsed(long userId, long bytes) {
        mapper.subUsed(userId, bytes);
    }

    /**
     * 对无需上传的秒传/分享保存直接原子记入已用容量。未完成上传应使用
     * {@link #tryReserve(long, long)}，从而避免 checkCapacity + addUsed 的 TOCTOU 竞态。
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

    public void tryReserve(long userId, long bytes) {
        if (bytes <= 0) return;
        getOrDefault(userId);
        if (mapper.tryReserve(userId, bytes) <= 0) {
            throw BizException.of(ErrorCode.QUOTA_EXCEEDED, "need=" + bytes);
        }
    }

    public void commitReservation(long userId, long bytes) {
        if (bytes <= 0) return;
        if (mapper.commitReservation(userId, bytes) <= 0) {
            throw new IllegalStateException("quota reservation missing for user=" + userId + ", bytes=" + bytes);
        }
    }

    public void releaseReservation(long userId, long bytes) {
        if (bytes <= 0) return;
        mapper.releaseReservation(userId, bytes);
    }
}
