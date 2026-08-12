package com.cloudchunk.core.upload.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.common.enums.UploadSessionStatus;
import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.util.MimeUtils;
import com.cloudchunk.contract.event.ChecksumRequested;
import com.cloudchunk.contract.event.IntegrationEventPublisher;
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
    private final IntegrationEventPublisher eventPublisher;
    private final com.cloudchunk.core.quota.service.QuotaService quotaService;

    public MergeTransactionService(UploadSessionMapper sessionMapper,
                                   FileMetaService fileMetaService,
                                   FileReferenceMapper referenceMapper,
                                   UserFileService userFileService,
                                   IntegrationEventPublisher eventPublisher,
                                   com.cloudchunk.core.quota.service.QuotaService quotaService) {
        this.sessionMapper = sessionMapper;
        this.fileMetaService = fileMetaService;
        this.referenceMapper = referenceMapper;
        this.userFileService = userFileService;
        this.eventPublisher = eventPublisher;
        this.quotaService = quotaService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void finalizeSuccess(UploadSession s, String storageType) {
        // 先锁会话行，与 cancel、过期清理保持同一个「会话优先」的加锁顺序。
        //
        // 不加这把锁会有两个后果。其一，本事务的第一次一致性读会在某个参与者取消之前建立快照，
        // 之后即使取消已经提交，事务内仍然看得见那个参与者，于是 commitReservation 找不到对应
        // 预留、抛出 IllegalStateException，整个合并事务回滚——一个人中途取消会把其他参与者的
        // 合并一起弄失败，而对象其实已经 compose 出来了。其二，refCount 会把已取消的参与者算进去，
        // 引用计数永远归不了零，物理对象再也不会被 GC 回收。
        UploadSession locked = sessionMapper.selectByFileIdForUpdate(s.getFileId());
        if (locked == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "upload_session:" + s.getFileId());
        }
        // 参与者列表只读一次：refCount 与随后的配额结算必须出自同一份快照，不能各读各的。
        List<FileReference> refs = referenceMapper.selectByFileId(s.getFileId());

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
        meta.setRefCount(Math.max(1, refs == null ? 0 : refs.size()));
        fileMetaService.insert(meta);

        sessionMapper.update(null, new LambdaUpdateWrapper<UploadSession>()
                .eq(UploadSession::getFileId, s.getFileId())
                .set(UploadSession::getStatus, UploadSessionStatus.COMPLETED));

        // 配额已在 init/加入会话时按参与者原子预留（reservation 模型），此处不再累加，
        // 否则会重复计费。取消/过期由 cancel / StaleSessionCleanupTask 释放。

        // 把合并完成的文件挂到每个参与者的网盘根目录（幂等，防合并重试产生重复）。
        if (refs == null || refs.isEmpty()) {
            quotaService.commitReservation(s.getOwnerId(), s.getFileSize());
            userFileService.createFileEntryIfAbsent(s.getOwnerId(), 0L, s.getFileId(),
                    s.getFileName(), s.getFileSize());
        } else {
            for (FileReference ref : refs) {
                quotaService.commitReservation(ref.getUserId(), s.getFileSize());
                String name = (ref.getFileName() == null || ref.getFileName().isBlank())
                        ? s.getFileName() : ref.getFileName();
                userFileService.createFileEntryIfAbsent(ref.getUserId(), 0L, s.getFileId(),
                        name, s.getFileSize());
            }
        }

        ChecksumRequested event = new ChecksumRequested();
        event.setFileId(s.getFileId());
        event.setBucket(s.getBucket());
        event.setObjectKey(s.getObjectKey());
        event.setExpectMd5(s.getFileMd5());
        event.setMimeType(s.getMimeType());
        event.setFileSize(s.getFileSize());
        eventPublisher.enqueue(MqTopics.CHECKSUM, s.getFileId(), event);
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
