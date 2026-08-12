package com.cloudchunk.core.file.dto;

import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.core.file.entity.FileMeta;

import java.time.LocalDateTime;

/** Public file projection; storage locator and internal ownership fields are intentionally excluded. */
public record FileView(
        String fileId,
        String fileMd5,
        String fileName,
        Long fileSize,
        String mimeType,
        String ext,
        FileStatus status,
        TranscodeStatus transcodeStatus,
        String thumbnailUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static FileView from(FileMeta meta) {
        return new FileView(meta.getFileId(), meta.getFileMd5(), meta.getFileName(), meta.getFileSize(),
                meta.getMimeType(), meta.getExt(), meta.getStatus(), meta.getTranscodeStatus(),
                meta.getThumbnailUrl() == null || meta.getThumbnailUrl().isBlank()
                        ? null : "/api/v1/file/" + meta.getFileId() + "/thumbnail",
                meta.getCreatedAt(), meta.getUpdatedAt());
    }
}
