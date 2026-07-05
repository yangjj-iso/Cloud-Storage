package com.cloudchunk.core.share.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.model.PageResult;
import com.cloudchunk.common.util.IdUtils;
import com.cloudchunk.core.drive.dto.UserFileItem;
import com.cloudchunk.core.drive.entity.UserFile;
import com.cloudchunk.core.drive.service.DriveZipService;
import com.cloudchunk.core.drive.service.UserFileService;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.quota.service.QuotaService;
import com.cloudchunk.core.share.dto.ShareDtos.ShareDetail;
import com.cloudchunk.core.share.dto.ShareDtos.ShareItem;
import com.cloudchunk.core.share.dto.ShareDtos.ShareResult;
import com.cloudchunk.core.share.entity.FileShare;
import com.cloudchunk.core.share.mapper.FileShareMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 文件 / 文件夹分享：创建带提取码的分享、公开查看/浏览、打包下载（复用网盘打包器）、
 * 递归转存到自己网盘。
 */
@Service
public class ShareService {

    private static final int MAX_SAVE_NODES = 20_000;
    private static final int MAX_SHARE_CODE_LENGTH = 64;
    private static final String CODE_HASH_PREFIX = "sha256$";
    private static final Pattern SHARE_ID_PATTERN = Pattern.compile("^[a-fA-F0-9]{16,32}$");
    private static final char[] CODE_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private final FileShareMapper shareMapper;
    private final UserFileService userFileService;
    private final DriveZipService driveZipService;
    private final FileMetaService fileMetaService;
    private final QuotaService quotaService;

    public ShareService(FileShareMapper shareMapper,
                        UserFileService userFileService,
                        DriveZipService driveZipService,
                        FileMetaService fileMetaService,
                        QuotaService quotaService) {
        this.shareMapper = shareMapper;
        this.userFileService = userFileService;
        this.driveZipService = driveZipService;
        this.fileMetaService = fileMetaService;
        this.quotaService = quotaService;
    }

    /* ============================ 拥有者操作（需登录） ============================ */

    public ShareResult createShare(long userId, long userFileId, int expireDays) {
        if (userFileId <= 0 || expireDays < 0 || expireDays > 3650) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid share parameters");
        }
        UserFile uf = userFileService.findByIdActive(userFileId, userId)
                .orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND));
        if (!uf.dir() && uf.getFileId() != null && !uf.getFileId().isEmpty()) {
            fileMetaService.getAvailableOrThrow(uf.getFileId());
        }

        String shareId = IdUtils.uuid32().substring(0, 16);
        String shareCode = genCode(6);
        LocalDateTime expireAt = expireDays > 0
                ? LocalDateTime.now().plusDays(expireDays) : null;

        FileShare s = new FileShare();
        s.setShareId(shareId);
        s.setUserId(userId);
        s.setUserFileId(userFileId);
        s.setFileId(uf.getFileId());
        s.setShareCode(hashShareCode(shareCode));
        s.setExpireAt(expireAt);
        s.setViewCount(0L);
        s.setSaveCount(0L);
        s.setStatus(0);
        shareMapper.insert(s);

        return new ShareResult(shareId, shareCode, "/s/" + shareId);
    }

    public PageResult<ShareItem> listShares(long userId, long page, long size) {
        long safeSize = Math.min(Math.max(size, 1), 100);
        Page<FileShare> p = new Page<>(Math.max(page, 1), safeSize);
        Page<FileShare> result = shareMapper.selectPage(p, new LambdaQueryWrapper<FileShare>()
                .eq(FileShare::getUserId, userId)
                .eq(FileShare::getStatus, 0)
                .orderByDesc(FileShare::getCreatedAt));

        List<Long> ufIds = result.getRecords().stream().map(FileShare::getUserFileId).toList();
        Map<Long, UserFile> ufMap = new HashMap<>();
        for (UserFile uf : userFileService.findByIds(ufIds)) {
            ufMap.put(uf.getId(), uf);
        }

        List<ShareItem> items = result.getRecords().stream().map(s -> {
            UserFile uf = ufMap.get(s.getUserFileId());
            String name = uf == null ? null : uf.getFileName();
            Long fsize = uf == null ? null : uf.getFileSize();
            return new ShareItem(s.getShareId(), s.getUserFileId(), s.getFileId(), name, fsize,
                    s.getExpireAt(), s.getViewCount(), s.getSaveCount(), s.getCreatedAt());
        }).toList();

        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), items);
    }

    public void cancelShare(String shareId, long userId) {
        requireValidShareId(shareId);
        shareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getShareId, shareId)
                .eq(FileShare::getUserId, userId)
                .eq(FileShare::getStatus, 0)
                .set(FileShare::getStatus, 1));
    }

    /* ============================ 公开访问（需提取码） ============================ */

    public ShareDetail getShare(String shareId, String shareCode) {
        FileShare share = validateShare(shareId, shareCode);
        incView(shareId);

        UserFile uf = userFileService.findByIdActive(share.getUserFileId(), share.getUserId())
                .orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND));

        String mime = null;
        if (uf.getFileId() != null && !uf.getFileId().isEmpty()) {
            FileMeta fm = fileMetaService.getAvailableOrThrow(uf.getFileId());
            mime = fm.getMimeType();
        }
        return new ShareDetail(share.getShareId(), uf.getFileId(), uf.getFileName(),
                uf.getFileSize(), mime, uf.dir());
    }

    /**
     * 浏览分享目录内容（parentId 0 = 分享根）。拒绝越出分享子树；单文件分享直接返回该文件。
     */
    public List<UserFileItem> listShareChildren(String shareId, String shareCode, long parentId) {
        FileShare share = validateShare(shareId, shareCode);
        UserFile root = userFileService.findByIdActive(share.getUserFileId(), share.getUserId())
                .orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND));
        if (!root.dir()) {
            fileMetaService.getAvailableOrThrow(root.getFileId());
            return List.of(UserFileItem.of(root));
        }
        long listParent = share.getUserFileId();
        if (parentId != 0 && parentId != share.getUserFileId()) {
            if (!isWithinSubtree(share.getUserId(), share.getUserFileId(), parentId)) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            listParent = parentId;
        }
        return userFileService.findByParentActive(share.getUserId(), listParent)
                .stream()
                .filter(this::isVisibleInPublicShare)
                .map(UserFileItem::of)
                .toList();
    }

    public DriveZipService.ZipPlan prepareShareArchive(String shareId, String shareCode) {
        FileShare share = validateShare(shareId, shareCode);
        DriveZipService.ZipPlan plan = driveZipService.prepareZip(share.getUserId(), List.of(share.getUserFileId()));
        incView(shareId);
        return plan;
    }

    public DriveZipService driveZip() {
        return driveZipService;
    }

    public record ShareFile(FileMeta meta, String fileName) {
    }

    /** 公开分享单文件直连下载：校验后返回文件元数据（对象流由调用方懒打开）。目录分享请用 download-zip。 */
    public ShareFile prepareShareFile(String shareId, String shareCode) {
        FileShare share = validateShare(shareId, shareCode);
        UserFile uf = userFileService.findByIdActive(share.getUserFileId(), share.getUserId())
                .orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND));
        if (uf.dir()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "use download-zip for a folder share");
        }
        if (uf.getFileId() == null || uf.getFileId().isEmpty()) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        }
        FileMeta fm = fileMetaService.getAvailableOrThrow(uf.getFileId());
        incView(shareId);
        return new ShareFile(fm, uf.getFileName());
    }

    /* ============================ 转存到我的网盘（需登录） ============================ */

    @Transactional(rollbackFor = Exception.class)
    public void saveToMyDrive(String shareId, String shareCode, long userId, long parentId) {
        FileShare share = validateShare(shareId, shareCode);
        if (userId == share.getUserId()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "cannot save your own share");
        }
        if (parentId != 0) {
            UserFile parent = userFileService.findByIdActive(parentId, userId)
                    .orElseThrow(() -> BizException.of(ErrorCode.INVALID_PARAMETER, "target directory not found"));
            if (!parent.dir()) {
                throw BizException.of(ErrorCode.INVALID_PARAMETER, "target is not a directory");
            }
        }
        UserFile root = userFileService.findByIdActive(share.getUserFileId(), share.getUserId())
                .orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND));

        saveNode(share.getUserId(), userId, parentId, root, new int[]{0});
        incSave(shareId);
    }

    private void saveNode(long sharerId, long userId, long destParentId, UserFile node, int[] count) {
        if (++count[0] > MAX_SAVE_NODES) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "too many entries to save in one request");
        }
        if (node.dir()) {
            UserFileItem dir = userFileService.mkdir(userId, destParentId, node.getFileName());
            for (UserFile child : userFileService.findByParentActive(sharerId, node.getId())) {
                saveNode(sharerId, userId, dir.id(), child, count);
            }
            return;
        }
        if (node.getFileId() == null || node.getFileId().isEmpty()) {
            return;
        }
        fileMetaService.getAvailableOrThrow(node.getFileId());
        long size = node.getFileSize() == null ? 0 : node.getFileSize();
        boolean added = fileMetaService.addReference(node.getFileId(), userId, node.getFileName());
        if (added) {
            try {
                quotaService.tryConsume(userId, size);
            } catch (BizException e) {
                fileMetaService.removeReference(node.getFileId(), userId);
                throw e;
            }
            fileMetaService.incRefCount(node.getFileId());
        }
        userFileService.createFileEntryIfAbsent(userId, destParentId, node.getFileId(), node.getFileName(), size);
    }

    /* ============================ helpers ============================ */

    private FileShare validateShare(String shareId, String shareCode) {
        requireValidShareId(shareId);
        if (shareCode != null && shareCode.length() > MAX_SHARE_CODE_LENGTH) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid share code");
        }
        FileShare share = shareMapper.selectOne(new LambdaQueryWrapper<FileShare>()
                .eq(FileShare::getShareId, shareId)
                .eq(FileShare::getStatus, 0));
        if (share == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!matchesShareCode(share.getShareCode(), shareCode)) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid share code");
        }
        if (share.getExpireAt() != null && share.getExpireAt().isBefore(LocalDateTime.now())) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "share expired");
        }
        return share;
    }

    private void requireValidShareId(String shareId) {
        if (shareId == null || !SHARE_ID_PATTERN.matcher(shareId).matches()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid shareId");
        }
    }

    private boolean isWithinSubtree(long sharerId, long rootId, long nodeId) {
        long cursor = nodeId;
        for (int i = 0; i < 200 && cursor != 0; i++) {
            if (cursor == rootId) {
                return true;
            }
            UserFile n = userFileService.findByIdActive(cursor, sharerId).orElse(null);
            if (n == null) {
                return false;
            }
            cursor = n.getParentId() == null ? 0 : n.getParentId();
        }
        return cursor == rootId;
    }

    private boolean isVisibleInPublicShare(UserFile node) {
        if (node.dir()) {
            return true;
        }
        if (node.getFileId() == null || node.getFileId().isEmpty()) {
            return false;
        }
        try {
            fileMetaService.getAvailableOrThrow(node.getFileId());
            return true;
        } catch (BizException e) {
            return false;
        }
    }

    private void incView(String shareId) {
        shareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getShareId, shareId)
                .setSql("view_count = view_count + 1"));
    }

    private void incSave(String shareId) {
        shareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getShareId, shareId)
                .setSql("save_count = save_count + 1"));
    }

    private static String genCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CODE_CHARS[RNG.nextInt(CODE_CHARS.length)]);
        }
        return sb.toString();
    }

    private static String hashShareCode(String code) {
        return CODE_HASH_PREFIX + sha256Base64(code == null ? "" : code);
    }

    private static boolean matchesShareCode(String stored, String provided) {
        String code = provided == null ? "" : provided;
        if (stored == null) {
            stored = "";
        }
        if (stored.startsWith(CODE_HASH_PREFIX)) {
            byte[] a = stored.getBytes(StandardCharsets.UTF_8);
            byte[] b = hashShareCode(code).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(a, b);
        }
        // 兼容历史明文提取码，后续可由后台迁移为 sha256$ 前缀格式。
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Base64(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
