package com.cloudchunk.core.drive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.drive.dto.UserFileItem;
import com.cloudchunk.core.drive.entity.UserFile;
import com.cloudchunk.core.drive.mapper.UserFileMapper;
import com.cloudchunk.core.file.entity.FileReference;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.mapper.FileReferenceMapper;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.quota.service.QuotaService;
import com.cloudchunk.storage.StorageStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 网盘（user_file）目录树服务：目录增删改查、移动（防环）、回收站软删/还原/彻底删除
 * （均按整棵子树递归处理），以及上传/秒传落盘到用户网盘。
 *
 * <h3>三张表的数据流转关系（关键）</h3>
 * <ul>
 *   <li><b>file_meta</b>：物理对象的元数据。一份内容（MD5 唯一）只有一行，被多人共享，
 *       带引用计数 {@code ref_count}。真正的 MinIO 对象删除只在引用计数归零时发生。</li>
 *   <li><b>file_reference</b>：谁"拥有/参与"了某个 file_id（用户 × 文件的多对多）。
 *       决定配额归属与访问权限。</li>
 *   <li><b>user_file</b>：用户视角的目录树节点（"我的网盘 A 目录下有这个文件"）。
 *       目录节点 file_id 为空；文件节点通过 file_id 指回 file_meta。同一 file_id 可以出现在
 *       多个目录（多个 user_file 行），但引用只有一份。</li>
 * </ul>
 * <p>因此：上传/转存 = 加 file_reference（预留配额）+ incRefCount + 建 user_file 行；
 * 删除 = 移 user_file 进回收站（仍占配额）→ 彻底删时 removeReference + decRefCount，
 * 计数归零才物理删 MinIO 对象并标记 file_meta 为 DELETED。</p>
 */
@Service
public class UserFileService {

    private static final Logger log = LoggerFactory.getLogger(UserFileService.class);

    /** 递归遍历节点上限，防止异常目录树导致失控。 */
    private static final int MAX_TREE_NODES = 100_000;

    private final UserFileMapper mapper;
    private final FileMetaService fileMetaService;
    private final FileReferenceMapper referenceMapper;
    private final QuotaService quotaService;
    private final StorageStrategyFactory storageFactory;

    public UserFileService(UserFileMapper mapper,
                           FileMetaService fileMetaService,
                           FileReferenceMapper referenceMapper,
                           QuotaService quotaService,
                           StorageStrategyFactory storageFactory) {
        this.mapper = mapper;
        this.fileMetaService = fileMetaService;
        this.referenceMapper = referenceMapper;
        this.quotaService = quotaService;
        this.storageFactory = storageFactory;
    }

    /* ============================ 目录/文件基础操作 ============================ */

    public UserFileItem mkdir(long userId, long parentId, String name) {
        if (name == null || name.isBlank()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "directory name is required");
        }
        if (parentId != 0) {
            UserFile parent = findByIdActive(parentId, userId)
                    .orElseThrow(() -> BizException.of(ErrorCode.INVALID_PARAMETER, "parent directory not found"));
            if (!parent.dir()) {
                throw BizException.of(ErrorCode.INVALID_PARAMETER, "parent is not a directory");
            }
        }
        UserFile dir = new UserFile();
        dir.setUserId(userId);
        dir.setParentId(parentId);
        dir.setFileName(name);
        dir.setIsDir(true);
        dir.setFileSize(0L);
        dir.setStatus(0);
        mapper.insert(dir);
        return UserFileItem.of(dir);
    }

    public List<UserFileItem> list(long userId, long parentId) {
        List<UserFile> files = mapper.selectList(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getParentId, parentId)
                .eq(UserFile::getStatus, 0)
                .orderByDesc(UserFile::getIsDir)
                .orderByDesc(UserFile::getCreatedAt));
        return files.stream().map(UserFileItem::of).toList();
    }

    public void rename(long id, long userId, String newName) {
        if (newName == null || newName.isBlank()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "new name is required");
        }
        int updated = mapper.update(null, new LambdaUpdateWrapper<UserFile>()
                .eq(UserFile::getId, id)
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getStatus, 0)
                .set(UserFile::getFileName, newName));
        if (updated <= 0) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        }
    }

    public void move(long id, long userId, long newParentId) {
        if (id == newParentId) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "cannot move into self");
        }
        findByIdActive(id, userId)
                .orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND));
        if (newParentId != 0) {
            UserFile parent = findByIdActive(newParentId, userId)
                    .orElseThrow(() -> BizException.of(ErrorCode.INVALID_PARAMETER, "target directory not found"));
            if (!parent.dir()) {
                throw BizException.of(ErrorCode.INVALID_PARAMETER, "target is not a directory");
            }
            // 从目标父节点向上回溯，若遇到自身则会形成环；同时防御异常父链自环。
            long cursor = newParentId;
            Set<Long> seen = new HashSet<>();
            for (int i = 0; cursor != 0; i++) {
                if (cursor == id) {
                    throw BizException.of(ErrorCode.INVALID_PARAMETER,
                            "cannot move: would create circular directory reference");
                }
                if (!seen.add(cursor) || i >= MAX_TREE_NODES) {
                    throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid directory hierarchy");
                }
                UserFile p = findByIdActive(cursor, userId).orElse(null);
                if (p == null) break;
                cursor = p.getParentId() == null ? 0 : p.getParentId();
            }
        }
        int updated = mapper.update(null, new LambdaUpdateWrapper<UserFile>()
                .eq(UserFile::getId, id)
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getStatus, 0)
                .set(UserFile::getParentId, newParentId));
        if (updated <= 0) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        }
    }

    public UserFileItem getFileInfo(long id, long userId) {
        UserFile uf = findByIdActive(id, userId)
                .orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND));
        return UserFileItem.of(uf);
    }

    /**
     * 上传/秒传完成后把文件挂到用户网盘（幂等：同一 (userId,fileId) 下已存在活跃条目则跳过，
     * 避免合并重试产生重复行）。
     */
    public void createFileEntryIfAbsent(long userId, long parentId, String fileId, String fileName, long fileSize) {
        List<UserFile> exists = findActiveByUserAndFileId(userId, fileId);
        for (UserFile e : exists) {
            if (parentId == (e.getParentId() == null ? 0 : e.getParentId())) {
                return;
            }
        }
        UserFile uf = new UserFile();
        uf.setUserId(userId);
        uf.setParentId(parentId);
        uf.setFileId(fileId);
        uf.setFileName(fileName == null || fileName.isBlank() ? "file" : fileName);
        uf.setIsDir(false);
        uf.setFileSize(fileSize);
        uf.setStatus(0);
        mapper.insert(uf);
    }

    /* ============================ 回收站 ============================ */

    public List<UserFileItem> listRecycleBin(long userId) {
        List<UserFile> files = mapper.selectList(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getStatus, 1)
                .orderByDesc(UserFile::getDeletedAt));
        Set<Long> recycledIds = new HashSet<>();
        for (UserFile f : files) {
            if (f.getId() != null) {
                recycledIds.add(f.getId());
            }
        }
        return files.stream()
                .filter(f -> {
                    Long parentId = f.getParentId();
                    return parentId == null || parentId == 0 || !recycledIds.contains(parentId);
                })
                .map(UserFileItem::of)
                .toList();
    }

    /**
     * 软删除一个节点，若为目录则连同整棵活跃子树一起进回收站。回收站内容仍占用配额，
     * 只有彻底删除时才释放引用和容量，避免用户通过回收站绕过容量限制。
     */
    @Transactional(rollbackFor = Exception.class)
    public void moveToRecycleBin(long id, long userId) {
        UserFile root = findByIdActive(id, userId)
                .orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now();
        for (UserFile n : selectSubtreeBounded(userId, root.getId(), 0)) {
            int updated = mapper.update(null, new LambdaUpdateWrapper<UserFile>()
                    .eq(UserFile::getId, n.getId())
                    .eq(UserFile::getUserId, userId)
                    .eq(UserFile::getStatus, 0)
                    .set(UserFile::getStatus, 1)
                    .set(UserFile::getDeletedAt, now));
        }
    }

    /** 还原一个回收站节点（及其回收站子树）；容量在回收站期间仍被占用，因此不重复增加配额。 */
    @Transactional(rollbackFor = Exception.class)
    public void restore(long id, long userId) {
        UserFile root = findByIdWithStatus(id, userId, 1)
                .orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND));
        boolean restoreToRoot = root.getParentId() != null
                && root.getParentId() != 0
                && findByIdActive(root.getParentId(), userId).isEmpty();
        for (UserFile n : selectSubtreeBounded(userId, root.getId(), 1)) {
            LambdaUpdateWrapper<UserFile> update = new LambdaUpdateWrapper<UserFile>()
                    .eq(UserFile::getId, n.getId())
                    .eq(UserFile::getUserId, userId)
                    .eq(UserFile::getStatus, 1)
                    .set(UserFile::getStatus, 0)
                    .set(UserFile::getDeletedAt, null);
            if (restoreToRoot && n.getId() != null && n.getId().equals(root.getId())) {
                update.set(UserFile::getParentId, 0L);
            }
            int updated = mapper.update(null, update);
        }
    }

    /** 彻底删除一个回收站节点（及其整棵子树），释放存储引用（无引用时物理删除对象）。 */
    @Transactional(rollbackFor = Exception.class)
    public void hardDelete(long id, long userId) {
        UserFile root = findByIdAnyStatus(id, userId)
                .orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND));
        if (root.getStatus() == null || root.getStatus() != 1) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER,
                    "file is not in recycle bin; move to recycle bin first");
        }
        List<UserFile> subtree = selectSubtreeBounded(userId, root.getId(), null);
        Map<String, Integer> deletingFileEntryCounts = new HashMap<>();
        for (UserFile n : subtree) {
            if (!n.dir() && n.getFileId() != null && !n.getFileId().isEmpty()) {
                deletingFileEntryCounts.merge(n.getFileId(), 1, Integer::sum);
            }
        }

        Set<String> released = new HashSet<>();
        for (UserFile n : subtree) {
            if (!n.dir() && n.getFileId() != null && !n.getFileId().isEmpty()) {
                int totalEntries = countUserFileEntries(userId, n.getFileId());
                int deletingEntries = deletingFileEntryCounts.getOrDefault(n.getFileId(), 0);
                if (totalEntries <= deletingEntries && released.add(n.getFileId())) {
                    quotaService.subUsed(userId, n.getFileSize() == null ? 0 : n.getFileSize());
                    releaseFileReference(userId, n.getFileId());
                }
            }
            mapper.delete(new LambdaQueryWrapper<UserFile>()
                    .eq(UserFile::getId, n.getId())
                    .eq(UserFile::getUserId, userId));
        }
    }

    /**
     * 把某用户对某文件的所有活跃网盘条目移入回收站（供旧版 /file/{id} 删除统一语义使用）。
     * 返回移入数量；回收站内容仍占配额，彻底删除时释放。
     */
    public int recycleByFileId(long userId, String fileId) {
        List<UserFile> entries = findActiveByUserAndFileId(userId, fileId);
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (UserFile e : entries) {
            int updated = mapper.update(null, new LambdaUpdateWrapper<UserFile>()
                    .eq(UserFile::getId, e.getId())
                    .eq(UserFile::getUserId, userId)
                    .eq(UserFile::getStatus, 0)
                    .set(UserFile::getStatus, 1)
                    .set(UserFile::getDeletedAt, now));
            if (updated > 0) n++;
        }
        return n;
    }

    /* ============================ helpers ============================ */

    public Optional<UserFile> findByIdActive(long id, long userId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getId, id).eq(UserFile::getUserId, userId).eq(UserFile::getStatus, 0)));
    }

    /** 批量按 id 查节点（任意状态、任意归属），用于分享列表回填名称，避免 N+1。 */
    public List<UserFile> findByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectBatchIds(ids);
    }

    private Optional<UserFile> findByIdWithStatus(long id, long userId, int status) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getId, id).eq(UserFile::getUserId, userId).eq(UserFile::getStatus, status)));
    }

    Optional<UserFile> findByIdAnyStatus(long id, long userId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getId, id).eq(UserFile::getUserId, userId)));
    }

    public List<UserFile> findByParentActive(long userId, long parentId) {
        return mapper.selectList(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getParentId, parentId)
                .eq(UserFile::getStatus, 0)
                .orderByDesc(UserFile::getIsDir)
                .orderByDesc(UserFile::getCreatedAt));
    }

    List<UserFile> findActiveByUserAndFileId(long userId, String fileId) {
        return mapper.selectList(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getFileId, fileId)
                .eq(UserFile::getStatus, 0));
    }

    public boolean hasActiveFileEntry(long userId, String fileId) {
        Long n = mapper.selectCount(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getFileId, fileId)
                .eq(UserFile::getStatus, 0)
                .eq(UserFile::getIsDir, false));
        return n != null && n > 0;
    }

    private int countUserFileEntries(long userId, String fileId) {
        Long n = mapper.selectCount(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getFileId, fileId));
        return n == null ? 0 : n.intValue();
    }

    private List<UserFile> selectSubtreeBounded(long userId, long rootId, Integer status) {
        List<UserFile> nodes = mapper.selectSubtree(userId, rootId, status, MAX_TREE_NODES);
        if (nodes.size() >= MAX_TREE_NODES) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER,
                    "too many entries in directory tree; split the operation into smaller batches");
        }
        return nodes;
    }

    /** 管理端：分页列出所有用户的活跃网盘条目。 */
    public Page<UserFile> pageAllActive(long page, long size) {
        long safe = Math.min(Math.max(size, 1), 100);
        Page<UserFile> p = new Page<>(Math.max(page, 1), safe);
        return mapper.selectPage(p, new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getStatus, 0)
                .orderByDesc(UserFile::getCreatedAt));
    }

    /**
     * 管理端彻底清除某文件：删除所有用户的网盘条目（释放各自配额）、删除所有引用、
     * 物理删除对象并标记 file_meta 已删。
     */
    @Transactional(rollbackFor = Exception.class)
    public void adminPurgeFile(String fileId) {
        FileMeta fm = fileMetaService.findById(fileId)
                .orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND));

        List<UserFile> entries = mapper.selectList(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getFileId, fileId)
                .eq(UserFile::getIsDir, false));

        Set<Long> quotaUsers = new HashSet<>();
        for (FileReference r : referenceMapper.selectByFileId(fileId)) {
            if (r.getUserId() != null) {
                quotaUsers.add(r.getUserId());
            }
        }
        if (quotaUsers.isEmpty()) {
            for (UserFile e : entries) {
                if (e.getUserId() != null) {
                    quotaUsers.add(e.getUserId());
                }
            }
        }

        long fileSize = fm.getFileSize() == null ? 0 : fm.getFileSize();
        for (Long userId : quotaUsers) {
            quotaService.subUsed(userId, fileSize);
        }

        if (!entries.isEmpty()) {
            mapper.delete(new LambdaQueryWrapper<UserFile>()
                    .eq(UserFile::getFileId, fileId)
                    .eq(UserFile::getIsDir, false));
        }
        referenceMapper.deleteByFileId(fileId);
        try {
            storageFactory.current().delete(fm.getBucket(), fm.getObjectKey());
        } catch (Exception ex) {
            log.warn("[Admin][GC] delete object {}/{} failed: {}", fm.getBucket(), fm.getObjectKey(), ex.getMessage());
        }
        fileMetaService.markDeletedAndClearRefs(fileId);
    }

    void releaseFileReference(long userId, String fileId) {
        fileMetaService.removeReference(fileId, userId);
        fileMetaService.decRefCount(fileId);
        if (fileMetaService.referenceCount(fileId) > 0) {
            return;
        }
        FileMeta fm = fileMetaService.findById(fileId).orElse(null);
        if (fm == null) return;
        try {
            storageFactory.current().delete(fm.getBucket(), fm.getObjectKey());
        } catch (Exception e) {
            log.warn("[GC] delete object {}/{} failed: {}", fm.getBucket(), fm.getObjectKey(), e.getMessage());
        }
        fileMetaService.markDeleted(fileId);
    }
}
