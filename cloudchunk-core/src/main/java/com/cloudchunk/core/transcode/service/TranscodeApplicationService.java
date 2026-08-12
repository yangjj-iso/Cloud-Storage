package com.cloudchunk.core.transcode.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.util.MimeUtils;
import com.cloudchunk.contract.event.IntegrationEventPublisher;
import com.cloudchunk.contract.event.TranscodeRequested;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.transcode.dto.TranscodeStatusView;
import com.cloudchunk.core.transcode.dto.TranscodeTaskView;
import com.cloudchunk.core.transcode.entity.TranscodeRecord;
import com.cloudchunk.core.transcode.mapper.TranscodeRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TranscodeApplicationService {

    private final TranscodeRecordMapper recordMapper;
    private final FileMetaService fileMetaService;
    private final IntegrationEventPublisher eventPublisher;

    public TranscodeApplicationService(TranscodeRecordMapper recordMapper,
                                       FileMetaService fileMetaService,
                                       IntegrationEventPublisher eventPublisher) {
        this.recordMapper = recordMapper;
        this.fileMetaService = fileMetaService;
        this.eventPublisher = eventPublisher;
    }

    public TranscodeStatusView status(String fileId, long userId) {
        FileMeta meta = fileMetaService.getAvailableForUserOrThrow(fileId, userId);
        List<TranscodeTaskView> records = recordMapper.selectList(new LambdaQueryWrapper<TranscodeRecord>()
                        .eq(TranscodeRecord::getFileId, fileId)
                        .orderByDesc(TranscodeRecord::getCreatedAt))
                .stream().map(TranscodeTaskView::from).toList();
        return new TranscodeStatusView(fileId, meta.getTranscodeStatus(), records);
    }

    @Transactional(rollbackFor = Exception.class)
    public void requestRetry(String fileId, long userId) {
        FileMeta meta = fileMetaService.getAvailableForUserOrThrow(fileId, userId);
        String tag = MimeUtils.tag(meta.getMimeType());
        if (tag == null || meta.getBucket() == null || meta.getObjectKey() == null) {
            throw BizException.of(ErrorCode.TRANSCODE_NOT_FOUND, fileId);
        }
        boolean claimed = fileMetaService.transitionTranscodeStatus(fileId,
                List.of(TranscodeStatus.NONE, TranscodeStatus.FAILED, TranscodeStatus.SKIP,
                        TranscodeStatus.SUCCESS),
                TranscodeStatus.QUEUED);
        if (!claimed) {
            throw BizException.of(ErrorCode.TRANSCODE_IN_PROGRESS, fileId);
        }

        TranscodeRequested event = new TranscodeRequested();
        event.setFileId(meta.getFileId());
        event.setMimeType(meta.getMimeType());
        event.setBucket(meta.getBucket());
        event.setObjectKey(meta.getObjectKey());
        event.setFileSize(meta.getFileSize());
        event.setExpectMd5(meta.getFileMd5());
        eventPublisher.enqueue(MqTopics.transcodeDestination(tag), fileId, event);
    }

    /** Atomically claims a legacy NONE row and creates exactly one compensating outbox event. */
    @Transactional(rollbackFor = Exception.class)
    public boolean requestIfMissing(String fileId) {
        FileMeta meta = fileMetaService.getOrThrow(fileId);
        if (meta.getTranscodeStatus() != TranscodeStatus.NONE) {
            return false;
        }
        String tag = MimeUtils.tag(meta.getMimeType());
        if (tag == null) {
            fileMetaService.transitionTranscodeStatus(fileId,
                    List.of(TranscodeStatus.NONE), TranscodeStatus.SKIP);
            return false;
        }
        if (!fileMetaService.transitionTranscodeStatus(fileId,
                List.of(TranscodeStatus.NONE), TranscodeStatus.QUEUED)) {
            return false;
        }

        TranscodeRequested event = new TranscodeRequested();
        event.setFileId(meta.getFileId());
        event.setMimeType(meta.getMimeType());
        event.setBucket(meta.getBucket());
        event.setObjectKey(meta.getObjectKey());
        event.setFileSize(meta.getFileSize());
        event.setExpectMd5(meta.getFileMd5());
        eventPublisher.enqueue(MqTopics.transcodeDestination(tag), fileId, event);
        return true;
    }
}
