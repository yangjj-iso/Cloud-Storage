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

    public void addUsed(long userId, long bytes) {
        mapper.addUsed(userId, bytes);
    }

    public void subUsed(long userId, long bytes) {
        mapper.subUsed(userId, bytes);
    }
}
