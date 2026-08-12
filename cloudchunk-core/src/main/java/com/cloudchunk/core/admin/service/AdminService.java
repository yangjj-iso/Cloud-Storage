package com.cloudchunk.core.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.model.PageResult;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.admin.dto.AdminDtos.AdminFileItem;
import com.cloudchunk.core.admin.dto.AdminDtos.AdminUserItem;
import com.cloudchunk.core.admin.entity.SystemSetting;
import com.cloudchunk.core.admin.dto.SettingView;
import com.cloudchunk.core.admin.mapper.SystemSettingMapper;
import com.cloudchunk.core.auth.entity.UserAccount;
import com.cloudchunk.core.auth.mapper.UserAccountMapper;
import com.cloudchunk.core.auth.service.PasswordHasher;
import com.cloudchunk.core.download.service.DownloadService;
import com.cloudchunk.core.drive.entity.UserFile;
import com.cloudchunk.core.drive.service.UserFileService;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.quota.entity.UserQuota;
import com.cloudchunk.core.quota.mapper.UserQuotaMapper;
import com.cloudchunk.core.quota.service.QuotaService;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.storage.model.GetRequest;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理端：用户管理、文件管理、系统设置。
 * <p>
 * 授权的第一道关口是 API 安全边界（{@code /api/v1/admin/**} 要求 ROLE_ADMIN）。这里的
 * {@link #requireAdminCaller()} 是纵深防御：任何新增的调用入口如果忘了配安全规则，
 * 也不会把管理能力泄漏给普通用户。
 */
@Service
public class AdminService {

    /** 账号启用状态，与 AuthService 保持一致。 */
    private static final int STATUS_ENABLED = 1;

    /** 允许写入的系统设置键白名单。 */
    private static final Set<String> ALLOWED_SETTING_KEYS = Set.of(
            "default_space_bytes", "email_template_verify", "email_template_reset", "download_speed_limit");

    private final UserAccountMapper userMapper;
    private final UserQuotaMapper quotaMapper;
    private final QuotaService quotaService;
    private final PasswordHasher passwordHasher;
    private final UserFileService userFileService;
    private final FileMetaService fileMetaService;
    private final StorageStrategyFactory storageFactory;
    private final SystemSettingMapper settingMapper;
    private final DownloadService downloadService;

    public AdminService(UserAccountMapper userMapper,
                        UserQuotaMapper quotaMapper,
                        QuotaService quotaService,
                        PasswordHasher passwordHasher,
                        UserFileService userFileService,
                        FileMetaService fileMetaService,
                        StorageStrategyFactory storageFactory,
                        SystemSettingMapper settingMapper,
                        DownloadService downloadService) {
        this.userMapper = userMapper;
        this.quotaMapper = quotaMapper;
        this.quotaService = quotaService;
        this.passwordHasher = passwordHasher;
        this.userFileService = userFileService;
        this.fileMetaService = fileMetaService;
        this.storageFactory = storageFactory;
        this.settingMapper = settingMapper;
        this.downloadService = downloadService;
    }

    /** 系统设置键。 */
    public static final String KEY_DOWNLOAD_SPEED_LIMIT = "download_speed_limit";

    /**
     * 断言当前调用线程属于一个启用中的管理员账号。
     * <p>
     * 只用于面向请求的管理操作；启动期的内部调用（如 SettingsInitializer 读取设置）
     * 没有用户上下文，不走这个入口。
     */
    private void requireAdminCaller() {
        UserAccount caller = userMapper.selectById(UserContext.requireUserId());
        if (caller == null || caller.getStatus() == null || caller.getStatus() != STATUS_ENABLED
                || !"admin".equals(caller.getRole())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    /* ============================ 用户管理 ============================ */

    public PageResult<AdminUserItem> listUsers(String keyword, long page, long size) {
        requireAdminCaller();
        long safe = Math.min(Math.max(size, 1), 100);
        Page<UserAccount> p = new Page<>(Math.max(page, 1), safe);
        LambdaQueryWrapper<UserAccount> w = new LambdaQueryWrapper<UserAccount>()
                .orderByDesc(UserAccount::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like(UserAccount::getUsername, keyword)
                    .or().like(UserAccount::getEmail, keyword));
        }
        Page<UserAccount> result = userMapper.selectPage(p, w);

        List<Long> ids = result.getRecords().stream().map(UserAccount::getId).toList();
        Map<Long, UserQuota> quotas = new HashMap<>();
        if (!ids.isEmpty()) {
            for (UserQuota q : quotaMapper.selectBatchIds(ids)) {
                quotas.put(q.getUserId(), q);
            }
        }

        List<AdminUserItem> items = result.getRecords().stream().map(u -> {
            UserQuota q = quotas.get(u.getId());
            return new AdminUserItem(u.getId(), u.getUsername(), u.getEmail(),
                    u.getRole() == null ? "user" : u.getRole(), u.getStatus(),
                    q == null ? null : q.getTotalBytes(), q == null ? null : q.getUsedBytes(),
                    u.getCreatedAt());
        }).toList();
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), items);
    }

    public void disableUser(long userId) {
        requireAdminCaller();
        int updated = userMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .eq(UserAccount::getId, userId).set(UserAccount::getStatus, 0));
        if (updated <= 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "user not found");
        }
    }

    public void enableUser(long userId) {
        requireAdminCaller();
        int updated = userMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .eq(UserAccount::getId, userId).set(UserAccount::getStatus, 1));
        if (updated <= 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "user not found");
        }
    }

    public void resetUserPassword(long userId, String newPassword) {
        requireAdminCaller();
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 72) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "password length must be 8-72 characters");
        }
        int updated = userMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .eq(UserAccount::getId, userId)
                .set(UserAccount::getPasswordHash, passwordHasher.hash(newPassword)));
        if (updated <= 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "user not found");
        }
    }

    public void setUserRole(long userId, String role) {
        requireAdminCaller();
        if (!"user".equals(role) && !"admin".equals(role)) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "role must be 'user' or 'admin'");
        }
        int updated = userMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .eq(UserAccount::getId, userId).set(UserAccount::getRole, role));
        if (updated <= 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "user not found");
        }
    }

    public void allocateSpace(long userId, long totalBytes) {
        requireAdminCaller();
        if (totalBytes < 0) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "totalBytes must be non-negative");
        }
        quotaService.allocateSpace(userId, totalBytes);
    }

    /* ============================ 文件管理 ============================ */

    public PageResult<AdminFileItem> listAllFiles(long page, long size) {
        requireAdminCaller();
        Page<UserFile> result = userFileService.pageAllActive(page, size);
        List<Long> userIds = result.getRecords().stream().map(UserFile::getUserId).toList();
        Map<Long, String> names = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (UserAccount u : userMapper.selectBatchIds(userIds)) {
                names.put(u.getId(), u.getUsername());
            }
        }
        List<AdminFileItem> items = result.getRecords().stream().map(f -> new AdminFileItem(
                f.getId(), f.getFileId(), f.getFileName(), f.getFileSize(), f.dir(),
                f.getUserId(), names.get(f.getUserId()), f.getStatus(), f.getCreatedAt())).toList();
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), items);
    }

    public void adminDeleteFile(String fileId) {
        requireAdminCaller();
        userFileService.adminPurgeFile(fileId);
    }

    /** 返回文件元数据（供管理端下载设置响应头）；对象流由调用方在写响应体时懒打开。 */
    public FileMeta adminFileMeta(String fileId) {
        requireAdminCaller();
        return fileMetaService.getAvailableOrThrow(fileId);
    }

    /**
     * 懒打开对象流，避免客户端提前断开导致的存储连接泄漏。
     * <p>
     * 这里不重复鉴权：调用方在写响应体之前必须先通过 {@link #adminFileMeta(String)} 取得
     * bucket/objectKey，而写响应体发生在过滤器清理用户上下文之后，此处已经读不到调用者身份。
     */
    public InputStream openObject(String bucket, String objectKey) {
        return storageFactory.current().get(new GetRequest(bucket, objectKey));
    }

    /* ============================ 系统设置 ============================ */

    public List<SystemSetting> getAllSettings() {
        return settingMapper.selectList(null);
    }

    public List<SettingView> getSettingViews() {
        requireAdminCaller();
        return getAllSettings().stream().map(SettingView::from).toList();
    }

    public String getSetting(String key) {
        SystemSetting s = settingMapper.selectOne(new LambdaQueryWrapper<SystemSetting>()
                .eq(SystemSetting::getSettingKey, key));
        return s == null ? null : s.getSettingValue();
    }

    public void setSetting(String key, String value, long userId) {
        requireAdminCaller();
        if (!ALLOWED_SETTING_KEYS.contains(key)) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "unknown setting key");
        }
        validateSettingValue(key, value);
        SystemSetting existing = settingMapper.selectOne(new LambdaQueryWrapper<SystemSetting>()
                .eq(SystemSetting::getSettingKey, key));
        if (existing == null) {
            SystemSetting s = new SystemSetting();
            s.setSettingKey(key);
            s.setSettingValue(value);
            s.setUpdatedBy(userId);
            settingMapper.insert(s);
        } else {
            settingMapper.update(null, new LambdaUpdateWrapper<SystemSetting>()
                    .eq(SystemSetting::getSettingKey, key)
                    .set(SystemSetting::getSettingValue, value)
                    .set(SystemSetting::getUpdatedBy, userId));
        }
        // 热更新：下载限速设置立即生效，无需重启。
        applyLiveSetting(key, value);
    }

    /** 把可热更新的设置立即应用到运行时组件。 */
    public void applyLiveSetting(String key, String value) {
        if (KEY_DOWNLOAD_SPEED_LIMIT.equals(key)) {
            long bps = 0;
            try {
                if (value != null && !value.isBlank()) {
                    bps = Long.parseLong(value.trim());
                }
            } catch (NumberFormatException ignored) {
                bps = 0;
            }
            downloadService.setSpeedLimit(bps);
        }
    }

    private void validateSettingValue(String key, String value) {
        if (KEY_DOWNLOAD_SPEED_LIMIT.equals(key) || "default_space_bytes".equals(key)) {
            if (value == null || value.isBlank()) {
                return;
            }
            try {
                long parsed = Long.parseLong(value.trim());
                if (parsed < 0) {
                    throw BizException.of(ErrorCode.INVALID_PARAMETER, key + " must be non-negative");
                }
            } catch (NumberFormatException e) {
                throw BizException.of(ErrorCode.INVALID_PARAMETER, key + " must be a number");
            }
        }
    }
}
