package com.cloudchunk.core.upload.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.common.enums.UploadSessionStatus;
import com.cloudchunk.common.util.MimeUtils;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.quota.service.QuotaService;
import com.cloudchunk.core.upload.entity.UploadSession;
import com.cloudchunk.core.upload.mapper.UploadSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 封装合并过程的 MySQL 事务部分。独立成 Service 是为了让 @Transactional 代理生效
 * （UploadService 自调用 doMerge 不会走代理）。
 */
@Service
public class MergeTransactionService {

    private final UploadSessionMapper sessionMapper;
    private final FileMetaService fileMetaService;
    private final QuotaService quotaService;

    public MergeTransactionService(UploadSessionMapper sessionMapper,
                                   FileMetaService fileMetaService,
                                   QuotaService quotaService) {
        this.sessionMapper = sessionMapper;
        this.fileMetaService = fileMetaService;
        this.quotaService = quotaService;
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
        meta.setRefCount(1);
        fileMetaService.insert(meta);

        sessionMapper.update(null, new LambdaUpdateWrapper<UploadSession>()
                .eq(UploadSession::getFileId, s.getFileId())
                .set(UploadSession::getStatus, UploadSessionStatus.COMPLETED));

        quotaService.addUsed(s.getOwnerId(), s.getFileSize());
    }

    public void markMerging(String fileId) {
        sessionMapper.update(null, new LambdaUpdateWrapper<UploadSession>()
                .eq(UploadSession::getFileId, fileId)
                .set(UploadSession::getStatus, UploadSessionStatus.MERGING));
    }
}
