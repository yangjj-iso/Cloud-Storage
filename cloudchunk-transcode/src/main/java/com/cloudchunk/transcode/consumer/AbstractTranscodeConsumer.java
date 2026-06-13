package com.cloudchunk.transcode.consumer;

import com.cloudchunk.common.constant.RedisKeys;
import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.common.exception.TranscodeException;
import com.cloudchunk.common.trace.TraceContext;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.transcode.entity.TranscodeRecord;
import com.cloudchunk.core.transcode.mapper.TranscodeRecordMapper;
import com.cloudchunk.infra.redis.RedisService;
import com.cloudchunk.mq.message.TranscodeMessage;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.transcode.TranscodeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDateTime;

public abstract class AbstractTranscodeConsumer implements RocketMQListener<TranscodeMessage> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private static final int MAX_RETRY_BEFORE_GIVE_UP = 8;

    @Autowired protected FileMetaService fileMetaService;
    @Autowired protected TranscodeRecordMapper recordMapper;
    @Autowired protected StorageStrategyFactory storageFactory;
    @Autowired protected RedisService redis;
    @Autowired protected ObjectMapper objectMapper;

    protected abstract String taskType();

    protected abstract TranscodeResult doTranscode(TranscodeMessage msg) throws Exception;

    @Override
    public void onMessage(TranscodeMessage msg) {
        TraceContext.set(msg.getTraceId());
        String doneKey = RedisKeys.transcodeDone(msg.getFileId(), taskType());
        int currentRetry = msg.getRetryCount();
        try {
            if (Boolean.TRUE.equals(redis.hasKey(doneKey))) {
                log.debug("transcode already done: {} {}", msg.getFileId(), taskType());
                return;
            }

            fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.RUNNING);
            TranscodeRecord record = newRecord(msg);
            recordMapper.insert(record);

            try {
                TranscodeResult result = doTranscode(msg);
                String extraJson = objectMapper.writeValueAsString(result.getData());
                fileMetaService.updateExtra(msg.getFileId(), extraJson, result.getThumbnailUrl());
                fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.SUCCESS);

                record.setStatus(2);
                record.setResult(extraJson);
                record.setFinishedAt(LocalDateTime.now());
                recordMapper.updateById(record);

                redis.set(doneKey, "1", Duration.ofDays(7));
                log.info("transcode ok: fileId={}, type={}", msg.getFileId(), taskType());
            } catch (TranscodeException te) {
                boolean giveUp = !te.isRetryable() || currentRetry >= MAX_RETRY_BEFORE_GIVE_UP;
                record.setStatus(giveUp ? 3 : 0);
                record.setErrorMsg(te.getMessage());
                record.setFinishedAt(LocalDateTime.now());
                recordMapper.updateById(record);
                if (!giveUp) {
                    log.warn("transcode retryable: fileId={}, type={}, retry={}, reason={}",
                            msg.getFileId(), taskType(), currentRetry, te.getMessage());
                    throw te;
                }
                log.error("[TRANSCODE-ALERT] giving up after {} retries: fileId={}, type={}, reason={}",
                        currentRetry, msg.getFileId(), taskType(), te.getMessage());
                fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.FAILED);
            } catch (Exception e) {
                record.setStatus(3);
                record.setErrorMsg(e.getMessage());
                record.setFinishedAt(LocalDateTime.now());
                recordMapper.updateById(record);
                fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.FAILED);
                log.error("[TRANSCODE-ALERT] transcode failed (non-retryable): fileId={}, type={}",
                        msg.getFileId(), taskType(), e);
            }
        } finally {
            TraceContext.clear();
        }
    }

    private TranscodeRecord newRecord(TranscodeMessage msg) {
        TranscodeRecord r = new TranscodeRecord();
        r.setFileId(msg.getFileId());
        r.setTaskType(taskType());
        r.setStatus(1);
        r.setRetryCount(msg.getRetryCount());
        r.setStartedAt(LocalDateTime.now());
        return r;
    }
}
