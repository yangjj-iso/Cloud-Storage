package com.cloudchunk.core.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 管理端 DTO 集合。
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record AdminUserItem(
            Long id,
            String username,
            String email,
            String role,
            Integer status,
            Long totalBytes,
            Long usedBytes,
            LocalDateTime createdAt
    ) {
    }

    public record AdminFileItem(
            Long id,
            String fileId,
            String fileName,
            Long fileSize,
            boolean isDir,
            Long userId,
            String username,
            Integer status,
            LocalDateTime createdAt
    ) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record SetRoleRequest(@NotBlank @Pattern(regexp = "^(user|admin)$") String role) {
    }

    public record AllocateSpaceRequest(@NotNull @Positive Long userId,
                                       @NotNull @PositiveOrZero Long totalBytes) {
    }

    public record SetSettingRequest(@NotBlank @Size(max = 64) String key,
                                    @Size(max = 10000) String value) {
    }
}
