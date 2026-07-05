package com.cloudchunk.core.upload.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.common.enums.UploadSessionStatus;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.util.MimeUtils;
import com.cloudchunk.core.drive.service.UserFileService;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.entity.FileReference;
import com.cloudchunk.core.file.mapper.FileReferenceMapper;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.upload.entity.UploadSession;
import com.cloudchunk.core.upload.mapper.UploadSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 封装合并过程的 MySQL 事务部分。独立成 Service 是为了让 @Transactional 代理生效
 * （UploadService 自调用 doMerge 不会走代理）。
 */
@Service
public class MergeTransactionService {

    private final UploadSessionMapper sessionMapper;
    private final FileMetaService fileMetaService;
    private final FileReferenceMapper referenceMapper;
    private final UserFileService userFileService;

    public MergeTransactionService(UploadSessionMapper sessionMapper,
                                   FileMetaService fileMetaService,
                                   FileReferenceMapper referenceMapper,
                                   UserFileService userFileService) {
        this.sessionMapper = sessionMapper;
        this.fileMetaService = fileMetaService;
        this.referenceMapper = referenceMapper;
        this.userFileService = userFileService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void finalizeSuccess(UploadSession s, String storageType) {
        FileMeta meta = new FileMeta();
        meta.setFileId(s.getFileId());
        meta.setFileMd5(s.getFileMd5());
        meta.setFileName(s.getFileName());
        meta.setFileSize(s.getFileSize());
        meta.setMimeType(s.getMimeType());
        meta.setExt(MimeUtils.extOf(s.getFileName()));
        meta.setStorageType(storageType);
        meta.setBucket(s.getBucket());
        meta.setObjectKey(s.getObjectKey());
        meta.setStatus(FileStatus.MERGED);
        meta.setTranscodeStatus(TranscodeStatus.NONE);
        meta.setOwnerId(s.getOwnerId());
        int refCount = Math.max(1, referenceMapper.countByFileId(s.getFileId()));
        meta.setRefCount(refCount);
        fileMetaService.insert(meta);

        sessionMapper.update(null, new LambdaUpdateWrapper<UploadSession>()
                .eq(UploadSession::getFileId, s.getFileId())
                .set(UploadSession::getStatus, UploadSessionStatus.COMPLETED));

        // 配额已在 init/加入会话时按参与者原子预留（reservation 模型），此处不再累加，
        // 否则会重复计费。取消/过期由 cancel / StaleSessionCleanupTask 释放。

        // 把合并完成的文件挂到每个参与者的网盘根目录（幂等，防合并重试产生重复）。
        List<FileReference> refs = referenceMapper.selectByFileId(s.getFileId());
        if (refs == null || refs.isEmpty()) {
            userFileService.createFileEntryIfAbsent(s.getOwnerId(), 0L, s.getFileId(),
                    s.getFileName(), s.getFileSize());
        } else {
            for (FileReference ref : refs) {
                String name = (ref.getFileName() == null || ref.getFileName().isBlank())
                        ? s.getFileName() : ref.getFileName();
                userFileService.createFileEntryIfAbsent(ref.getUserId(), 0L, s.getFileId(),
                        name, s.getFileSize());
            }
        }
    }

    public void markMerging(String fileId) {
        int updated = sessionMapper.update(null, new LambdaUpdateWrapper<UploadSession>()
                .eq(UploadSession::getFileId, fileId)
                .in(UploadSession::getStatus, UploadSessionStatus.RUNNING, UploadSessionStatus.MERGING)
                .set(UploadSession::getStatus, UploadSessionStatus.MERGING));
        if (updated <= 0) {
            throw BizException.of(ErrorCode.UPLOAD_SESSION_EXPIRED, fileId);
        }
    }
}
