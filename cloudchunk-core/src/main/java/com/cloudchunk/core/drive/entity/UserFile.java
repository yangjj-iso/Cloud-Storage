package com.cloudchunk.core.drive.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户网盘中的一个文件或目录节点。
 *
 * <p>支持目录树（parentId）、重命名（fileName）、移动（parentId）、回收站
 * （status=1 + deletedAt）。目录节点 fileId 为空；文件节点 fileId 指向 file_meta。</p>
 */
@TableName("user_file")
public class UserFile {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 目录节点为空；文件节点指向 file_meta.file_id */
    private String fileId;

    /** 0 = 根目录 */
    private Long parentId;

    private String fileName;

    @TableField("is_dir")
    private Boolean isDir;

    private Long fileSize;

    /** 0 = 正常，1 = 回收站 */
    private Integer status;

    private LocalDateTime deletedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Boolean getIsDir() { return isDir; }
    public void setIsDir(Boolean isDir) { this.isDir = isDir; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public boolean dir() { return Boolean.TRUE.equals(isDir); }
}
