package com.cloudchunk.core.upload.service;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.common.util.MimeUtils;
import com.cloudchunk.contract.event.BlobBroken;
import com.cloudchunk.contract.event.ChecksumRequested;
import com.cloudchunk.contract.event.IntegrationEventPublisher;
import com.cloudchunk.contract.event.TranscodeRequested;
import com.cloudchunk.contract.storage.ObjectDeletionQueue;
import com.cloudchunk.core.file.service.FileMetaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits checksum state and its follow-up event atomically through the outbox. */
@Service
public class ChecksumTransitionService {

    private final FileMetaService fileMetaService;
    private final IntegrationEventPublisher eventPublisher;
    private final ObjectDeletionQueue deletionQueue;

    public ChecksumTransitionService(FileMetaService fileMetaService,
                                     IntegrationEventPublisher eventPublisher,
                                     ObjectDeletionQueue deletionQueue) {
        this.fileMetaService = fileMetaService;
        this.eventPublisher = eventPublisher;
        this.deletionQueue = deletionQueue;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markAvailableAndRequestTranscode(ChecksumRequested source) {
        if (!fileMetaService.transitionStatus(source.getFileId(), FileStatus.MERGED, FileStatus.AVAILABLE)) {
            return false;
        }

        String tag = MimeUtils.tag(source.getMimeType());
        if (tag == null) {
            fileMetaService.updateTranscodeStatus(source.getFileId(), TranscodeStatus.SKIP);
            return true;
        }

        TranscodeRequested event = new TranscodeRequested();
        event.setFileId(source.getFileId());
        event.setMimeType(source.getMimeType());
        event.setBucket(source.getBucket());
        event.setObjectKey(source.getObjectKey());
        event.setFileSize(source.getFileSize());
        event.setExpectMd5(source.getExpectMd5());
        eventPublisher.enqueue(MqTopics.transcodeDestination(tag), source.getFileId(), event);
        fileMetaService.updateTranscodeStatus(source.getFileId(), TranscodeStatus.QUEUED);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markBroken(ChecksumRequested source, String reason) {
        if (!fileMetaService.transitionStatus(source.getFileId(), FileStatus.MERGED, FileStatus.BROKEN)) {
            return false;
        }
        eventPublisher.enqueue(MqTopics.BROKEN, source.getFileId(), BlobBroken.of(source.getFileId(), reason));
        deletionQueue.enqueue(source.getFileId(), source.getBucket(), source.getObjectKey(), false);
        return true;
    }
}
