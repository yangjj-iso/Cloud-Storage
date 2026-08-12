package com.cloudchunk.core.quota.dto;

import com.cloudchunk.core.quota.entity.UserQuota;

public record QuotaView(Long userId, Long totalBytes, Long usedBytes, Integer fileCount,
                        Long reservedBytes, Integer reservedFileCount) {
    public static QuotaView from(UserQuota quota) {
        return new QuotaView(quota.getUserId(), quota.getTotalBytes(), quota.getUsedBytes(), quota.getFileCount(),
                quota.getReservedBytes(), quota.getReservedFileCount());
    }
}
