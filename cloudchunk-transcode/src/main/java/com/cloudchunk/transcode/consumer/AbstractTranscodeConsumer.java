package com.cloudchunk.transcode.consumer;

import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.common.exception.TranscodeException;
import com.cloudchunk.contract.event.TranscodeRequested;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.transcode.entity.TranscodeRecord;
import com.cloudchunk.core.transcode.mapper.TranscodeRecordMapper;
import com.cloudchunk.infra.inbox.InboxService;
import com.cloudchunk.infra.tracing.IntegrationEventTracing;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.transcode.TranscodeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;

public abstract class AbstractTranscodeConsumer implements RocketMQListener<TranscodeRequested> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired protected FileMetaService fileMetaService;
    @Autowired protected TranscodeRecordMapper recordMapper;
    @Autowired protected StorageStrategyFactory storageFactory;
    @Autowired protected InboxService inboxService;
    @Autowired protected IntegrationEventTracing eventTracing;
    @Autowired protected ObjectMapper objectMapper;

    protected abstract String taskType();

    protected abstract TranscodeResult doTranscode(TranscodeRequested msg) throws Exception;

    @Override
    public void onMessage(TranscodeRequested msg) {
        try (var trace = eventTracing.startConsumer(msg, "transcode " + taskType())) {
            try {
                processMessage(msg);
            } catch (RuntimeException e) {
                trace.error(e);
                throw e;
            }
        }
    }

    private void processMessage(TranscodeRequested msg) {
        String eventId = eventId(msg);
        InboxService.AcquireResult acquired = inboxService.acquire(eventId, "TranscodeRequested:" + taskType());
        if (acquired == InboxService.AcquireResult.ALREADY_DONE) {
            return;
        }
        if (acquired == InboxService.AcquireResult.BUSY) {
            throw new IllegalStateException("transcode event is already being processed: " + eventId);
        }
        TranscodeRecord record = beginRecord(msg, eventId);
        if (Integer.valueOf(2).equals(record.getStatus())) {
            inboxService.markDone(eventId);
            return;
        }

        fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.RUNNING);

        try {
            TranscodeResult result = doTranscode(msg);
            String extraJson = objectMapper.writeValueAsString(result.getData());
            fileMetaService.updateExtra(msg.getFileId(), extraJson, result.getThumbnailUrl());
            fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.SUCCESS);

            record.setStatus(2);
            record.setResult(extraJson);
            record.setFinishedAt(LocalDateTime.now());
            recordMapper.updateById(record);

            inboxService.markDone(eventId);
            log.info("transcode ok: fileId={}, type={}", msg.getFileId(), taskType());
        } catch (TranscodeException te) {
            record.setStatus(te.isRetryable() ? 0 : 3);
            record.setErrorMsg(te.getMessage());
            record.setFinishedAt(LocalDateTime.now());
            recordMapper.updateById(record);
            if (te.isRetryable()) {
                fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.QUEUED);
                inboxService.markFailed(eventId, te);
                log.warn("transcode retryable: fileId={}, type={}, attempt={}, reason={}",
                        msg.getFileId(), taskType(), record.getRetryCount(), te.getMessage());
                throw te;
            }
            fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.FAILED);
            inboxService.markDone(eventId);
            log.error("[TRANSCODE-ALERT] non-retryable: fileId={}, type={}, reason={}",
                    msg.getFileId(), taskType(), te.getMessage());
        } catch (Exception e) {
            record.setStatus(0);
            record.setErrorMsg(e.getMessage());
            record.setFinishedAt(LocalDateTime.now());
            recordMapper.updateById(record);
            fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.QUEUED);
            inboxService.markFailed(eventId, e);
            throw new RuntimeException("transcode failed: " + msg.getFileId(), e);
        }
    }

    private TranscodeRecord beginRecord(TranscodeRequested msg, String eventId) {
        TranscodeRecord existing = recordMapper.selectOne(new LambdaQueryWrapper<TranscodeRecord>()
                .eq(TranscodeRecord::getEventId, eventId)
                .eq(TranscodeRecord::getTaskType, taskType())
                .last("limit 1"));
        if (existing != null && Integer.valueOf(2).equals(existing.getStatus())) {
            return existing;
        }
        if (existing != null) {
            existing.setStatus(1);
            existing.setRetryCount((existing.getRetryCount() == null ? 0 : existing.getRetryCount()) + 1);
            existing.setErrorMsg(null);
            existing.setStartedAt(LocalDateTime.now());
            existing.setFinishedAt(null);
            recordMapper.updateById(existing);
            return existing;
        }

        TranscodeRecord r = new TranscodeRecord();
        r.setEventId(eventId);
        r.setFileId(msg.getFileId());
        r.setTaskType(taskType());
        r.setStatus(1);
        r.setRetryCount(0);
        r.setStartedAt(LocalDateTime.now());
        try {
            recordMapper.insert(r);
            return r;
        } catch (DuplicateKeyException race) {
            return recordMapper.selectOne(new LambdaQueryWrapper<TranscodeRecord>()
                    .eq(TranscodeRecord::getEventId, eventId)
                    .eq(TranscodeRecord::getTaskType, taskType())
                    .last("limit 1"));
        }
    }

    private String eventId(TranscodeRequested msg) {
        if (msg.getEventId() != null && !msg.getEventId().isBlank()) return msg.getEventId();
        return "legacy-transcode-" + taskType() + "-" + msg.getFileId();
    }
}
