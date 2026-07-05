package com.cloudchunk.core.drive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 网盘目录操作的请求体集合。
 */
public final class DriveRequests {

    private DriveRequests() {
    }

    /** 批量 / 文件夹打包下载：ids 可混合文件与目录，单个目录 id 即整目录打包。 */
    public record ZipRequest(@NotEmpty @Size(max = 1000) List<@NotNull @Positive Long> ids) {
    }

    public record MkdirRequest(@PositiveOrZero Long parentId, @NotBlank @Size(max = 512) String name) {
        public long parentIdOrRoot() {
            return parentId == null ? 0L : parentId;
        }
    }

    public record RenameRequest(@NotNull @Positive Long id, @NotBlank @Size(max = 512) String newName) {
    }

    public record MoveRequest(@NotNull @Positive Long id, @PositiveOrZero Long newParentId) {
        public long newParentIdOrRoot() {
            return newParentId == null ? 0L : newParentId;
        }
    }
}
