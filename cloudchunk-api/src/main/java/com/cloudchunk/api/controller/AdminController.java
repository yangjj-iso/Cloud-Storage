package com.cloudchunk.api.controller;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.model.PageResult;
import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.admin.dto.AdminDtos.AllocateSpaceRequest;
import com.cloudchunk.core.admin.dto.AdminDtos.AdminFileItem;
import com.cloudchunk.core.admin.dto.AdminDtos.AdminUserItem;
import com.cloudchunk.core.admin.dto.AdminDtos.ResetPasswordRequest;
import com.cloudchunk.core.admin.dto.AdminDtos.SetRoleRequest;
import com.cloudchunk.core.admin.dto.AdminDtos.SetSettingRequest;
import com.cloudchunk.core.admin.entity.SystemSetting;
import com.cloudchunk.core.admin.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 管理端 API（需 admin 角色，由 AdminAuthFilter 拦截）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /* -------- 用户 -------- */

    @GetMapping("/users")
    public R<PageResult<AdminUserItem>> listUsers(@RequestParam(required = false) String keyword,
                                                  @RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size) {
        return R.ok(adminService.listUsers(keyword, page, size));
    }

    @PostMapping("/users/{userId}/disable")
    public R<Void> disableUser(@PathVariable long userId) {
        if (userId == UserContext.requireUserId()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "cannot disable your own account");
        }
        adminService.disableUser(userId);
        return R.ok();
    }

    @PostMapping("/users/{userId}/enable")
    public R<Void> enableUser(@PathVariable long userId) {
        adminService.enableUser(userId);
        return R.ok();
    }

    @PostMapping("/users/{userId}/reset-password")
    public R<Void> resetPassword(@PathVariable long userId, @Valid @RequestBody ResetPasswordRequest req) {
        adminService.resetUserPassword(userId, req.newPassword());
        return R.ok();
    }

    @PostMapping("/users/{userId}/role")
    public R<Void> setRole(@PathVariable long userId, @Valid @RequestBody SetRoleRequest req) {
        if (userId == UserContext.requireUserId() && !"admin".equals(req.role())) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "cannot change your own role");
        }
        adminService.setUserRole(userId, req.role());
        return R.ok();
    }

    @PostMapping("/users/space")
    public R<Void> allocateSpace(@Valid @RequestBody AllocateSpaceRequest req) {
        adminService.allocateSpace(req.userId(), req.totalBytes());
        return R.ok();
    }

    /* -------- 文件 -------- */

    @GetMapping("/files")
    public R<PageResult<AdminFileItem>> listFiles(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size) {
        return R.ok(adminService.listAllFiles(page, size));
    }

    @DeleteMapping("/files/{fileId}")
    public R<Void> deleteFile(@PathVariable String fileId) {
        adminService.adminDeleteFile(fileId);
        return R.ok();
    }

    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String fileId) {
        var meta = adminService.adminFileMeta(fileId);
        String contentType = meta.getMimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : meta.getMimeType();
        String disposition = "attachment; filename*=UTF-8''"
                + URLEncoder.encode(meta.getFileName(), StandardCharsets.UTF_8);
        String bucket = meta.getBucket();
        String objectKey = meta.getObjectKey();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=600");
        // 懒打开对象流：仅在真正写响应体时回源。
        StreamingResponseBody body = out -> {
            try (InputStream in = adminService.openObject(bucket, objectKey)) {
                in.transferTo(out);
                out.flush();
            }
        };
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /* -------- 系统设置 -------- */

    @GetMapping("/settings")
    public R<List<SystemSetting>> getSettings() {
        return R.ok(adminService.getAllSettings());
    }

    @PostMapping("/settings")
    public R<Void> setSetting(@Valid @RequestBody SetSettingRequest req) {
        long userId = UserContext.requireUserId();
        adminService.setSetting(req.key(), req.value(), userId);
        return R.ok();
    }
}
