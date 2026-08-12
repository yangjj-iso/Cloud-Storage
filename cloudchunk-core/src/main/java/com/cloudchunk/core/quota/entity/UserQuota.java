package com.cloudchunk.core.quota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("user_quota")
public class UserQuota {

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;
    private Long totalBytes;
    private Long usedBytes;
    private Integer fileCount;
    private Long reservedBytes;
    private Integer reservedFileCount;
    private LocalDateTime updatedAt;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(Long totalBytes) { this.totalBytes = totalBytes; }
    public Long getUsedBytes() { return usedBytes; }
    public void setUsedBytes(Long usedBytes) { this.usedBytes = usedBytes; }
    public Integer getFileCount() { return fileCount; }
    public void setFileCount(Integer fileCount) { this.fileCount = fileCount; }
    public Long getReservedBytes() { return reservedBytes; }
    public void setReservedBytes(Long reservedBytes) { this.reservedBytes = reservedBytes; }
    public Integer getReservedFileCount() { return reservedFileCount; }
    public void setReservedFileCount(Integer reservedFileCount) { this.reservedFileCount = reservedFileCount; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
