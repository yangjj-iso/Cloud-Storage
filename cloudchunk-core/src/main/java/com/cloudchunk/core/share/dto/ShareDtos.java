package com.cloudchunk.core.share.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 分享相关的 DTO 集合。
 */
public final class ShareDtos {

    private ShareDtos() {
    }

    public record ShareResult(String shareId, String shareCode, String shareUrl) {
    }

    public record ShareItem(
            String shareId,
            Long userFileId,
            String fileId,
            String fileName,
            Long fileSize,
            LocalDateTime expireAt,
            Long viewCount,
            Long saveCount,
            LocalDateTime createdAt
    ) {
    }

    public record ShareDetail(
            String shareId,
            String fileId,
            String fileName,
            Long fileSize,
            String mimeType,
            boolean isDir
    ) {
    }

    public record CreateShareRequest(@NotNull @Positive Long userFileId,
                                     @PositiveOrZero @Max(3650) Integer expireDays) {
        public int expireDaysOrZero() {
            return expireDays == null ? 0 : expireDays;
        }
    }

    public record SaveToDriveRequest(@NotBlank @Size(max = 32) @Pattern(regexp = "^[a-fA-F0-9]{16,32}$") String shareId,
                                     @NotBlank @Size(max = 64) String shareCode,
                                     @PositiveOrZero Long parentId) {
        public long parentIdOrRoot() {
            return parentId == null ? 0L : parentId;
        }
    }
}
