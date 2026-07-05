package com.cloudchunk.core.drive.dto;

import com.cloudchunk.core.drive.entity.UserFile;

import java.time.LocalDateTime;

/**
 * 网盘节点的对外视图。
 */
public record UserFileItem(
        Long id,
        String fileId,
        Long parentId,
        String fileName,
        boolean isDir,
        Long fileSize,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static UserFileItem of(UserFile uf) {
        return new UserFileItem(
                uf.getId(),
                uf.getFileId(),
                uf.getParentId(),
                uf.getFileName(),
                uf.dir(),
                uf.getFileSize(),
                uf.getStatus(),
                uf.getCreatedAt(),
                uf.getUpdatedAt()
        );
    }
}
