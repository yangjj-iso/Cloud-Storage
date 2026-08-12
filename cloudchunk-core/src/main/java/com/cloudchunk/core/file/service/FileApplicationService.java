package com.cloudchunk.core.file.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.model.PageResult;
import com.cloudchunk.core.download.service.DownloadService;
import com.cloudchunk.core.drive.service.UserFileService;
import com.cloudchunk.core.file.dto.FileView;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.mapper.FileMetaMapper;
import com.cloudchunk.core.quota.service.QuotaService;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.storage.model.GetRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

@Service
public class FileApplicationService {

    @FunctionalInterface
    public interface InputStreamOpener { InputStream open(); }
    public record ThumbnailStream(InputStreamOpener opener) {}
    public record DownloadDescriptor(FileView file, DownloadService.DownloadStream stream) {}
    public record DeleteResult(String fileId, boolean recycled, boolean deleted) {}

    private final FileMetaService fileMetaService;
    private final DownloadService downloadService;
    private final FileMetaMapper fileMetaMapper;
    private final QuotaService quotaService;
    private final StorageStrategyFactory storageFactory;
    private final UserFileService userFileService;

    public FileApplicationService(FileMetaService fileMetaService,
                                  DownloadService downloadService,
                                  FileMetaMapper fileMetaMapper,
                                  QuotaService quotaService,
                                  StorageStrategyFactory storageFactory,
                                  UserFileService userFileService) {
        this.fileMetaService = fileMetaService;
        this.downloadService = downloadService;
        this.fileMetaMapper = fileMetaMapper;
        this.quotaService = quotaService;
        this.storageFactory = storageFactory;
        this.userFileService = userFileService;
    }

    public FileView get(String fileId, long userId) {
        requireActiveEntry(fileId, userId);
        return FileView.from(fileMetaService.getAccessibleOrThrow(fileId, userId));
    }

    public ThumbnailStream thumbnail(String fileId, long userId) {
        FileMeta meta = fileMetaService.getAvailableForUserOrThrow(fileId, userId);
        requireActiveEntry(fileId, userId);
        String key = meta.getThumbnailUrl();
        if (key == null || key.isBlank()) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND, "no thumbnail");
        }
        return new ThumbnailStream(() -> storageFactory.current().get(new GetRequest(meta.getBucket(), key)));
    }

    public DownloadService.PresignedUrl presign(String fileId, Duration ttl, long userId) {
        requireActiveEntry(fileId, userId);
        return downloadService.presign(fileId, ttl, userId);
    }

    public DownloadDescriptor openDownload(String fileId, String range, long userId) {
        FileMeta meta = fileMetaService.getAvailableForUserOrThrow(fileId, userId);
        requireActiveEntry(fileId, userId);
        return new DownloadDescriptor(FileView.from(meta), downloadService.open(fileId, range, userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public DeleteResult delete(String fileId, long userId) {
        int recycled = userFileService.recycleByFileId(userId, fileId);
        if (recycled > 0) return new DeleteResult(fileId, true, false);

        // Repeating DELETE after the first call must not release quota/reference while a
        // restorable recycle-bin node still exists. Hard deletion belongs to the drive API.
        if (userFileService.hasAnyFileEntry(userId, fileId)) {
            return new DeleteResult(fileId, true, false);
        }

        FileMeta before = fileMetaService.getAccessibleOrThrow(fileId, userId);
        boolean removed = fileMetaService.removeReference(fileId, userId);
        if (removed) {
            fileMetaService.decRefCount(fileId);
            quotaService.subUsed(userId, before.getFileSize());
        }
        FileMeta meta = fileMetaService.getOrThrow(fileId);
        boolean deleted = meta.getRefCount() != null && meta.getRefCount() <= 0;
        if (deleted) userFileService.scheduleDeletion(fileId);
        return new DeleteResult(fileId, false, deleted);
    }

    public PageResult<FileView> list(long page, long size, String mimePrefix, String keyword, long userId) {
        long safeSize = Math.min(Math.max(size, 1), 100);
        Page<FileMeta> result = fileMetaMapper.selectActiveUserFilePage(
                new Page<>(Math.max(page, 1), safeSize), userId,
                normalize(mimePrefix), normalize(keyword), FileStatus.AVAILABLE.getCode());
        List<FileView> items = result.getRecords().stream()
                .map(FileView::from).toList();
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), items);
    }

    private void requireActiveEntry(String fileId, long userId) {
        if (!userFileService.hasActiveFileEntry(userId, fileId)) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND, fileId);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
