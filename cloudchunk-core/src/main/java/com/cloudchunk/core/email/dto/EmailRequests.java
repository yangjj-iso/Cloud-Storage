package com.cloudchunk.core.email.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 邮箱验证相关请求体。
 */
public final class EmailRequests {

    private EmailRequests() {
    }

    public record SendCodeRequest(@NotBlank @Email @Size(max = 128) String email,
                                  @NotBlank @Pattern(regexp = "^(register|reset|bind)$") String type) {
    }

    public record ResetPasswordRequest(@NotBlank @Email @Size(max = 128) String email,
                                       @NotBlank @Size(min = 4, max = 16) String code,
                                       @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record ChangeEmailRequest(@NotBlank @Email @Size(max = 128) String newEmail,
                                     @NotBlank @Size(min = 4, max = 16) String code) {
    }
}
