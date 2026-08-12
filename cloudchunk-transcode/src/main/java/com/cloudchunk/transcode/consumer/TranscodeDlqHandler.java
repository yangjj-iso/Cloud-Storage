package com.cloudchunk.transcode.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.contract.event.TranscodeRequested;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.transcode.entity.TranscodeRecord;
import com.cloudchunk.core.transcode.mapper.TranscodeRecordMapper;
import com.cloudchunk.infra.tracing.IntegrationEventTracing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "cloudchunk.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
public class TranscodeDlqHandler {

    private static final Logger log = LoggerFactory.getLogger(TranscodeDlqHandler.class);
    private final FileMetaService fileMetaService;
    private final TranscodeRecordMapper recordMapper;
    private final IntegrationEventTracing eventTracing;

    public TranscodeDlqHandler(FileMetaService fileMetaService,
                               TranscodeRecordMapper recordMapper,
                               IntegrationEventTracing eventTracing) {
        this.fileMetaService = fileMetaService;
        this.recordMapper = recordMapper;
        this.eventTracing = eventTracing;
    }

    public void handle(TranscodeRequested event, String taskType) {
        try (var ignored = eventTracing.startConsumer(event, "transcode dlq " + taskType)) {
            handleTraced(event, taskType);
        }
    }

    private void handleTraced(TranscodeRequested event, String taskType) {
        String eventId = event.getEventId() == null || event.getEventId().isBlank()
                ? "legacy-transcode-" + taskType + "-" + event.getFileId() : event.getEventId();
        log.error("[TRANSCODE-DLQ-ALERT] eventId={}, fileId={}, type={}, producedAt={}",
                eventId, event.getFileId(), taskType, event.getOccurredAt());
        fileMetaService.updateTranscodeStatus(event.getFileId(), TranscodeStatus.FAILED);

        TranscodeRecord record = recordMapper.selectOne(new LambdaQueryWrapper<TranscodeRecord>()
                .eq(TranscodeRecord::getEventId, eventId)
                .eq(TranscodeRecord::getTaskType, taskType)
                .last("limit 1"));
        if (record != null) {
            record.setStatus(3);
            record.setErrorMsg("Exhausted broker retries and entered DLQ");
            record.setFinishedAt(LocalDateTime.now());
            recordMapper.updateById(record);
            return;
        }

        record = new TranscodeRecord();
        record.setEventId(eventId);
        record.setFileId(event.getFileId());
        record.setTaskType(taskType);
        record.setStatus(3);
        record.setRetryCount(0);
        record.setErrorMsg("Exhausted broker retries and entered DLQ");
        record.setStartedAt(LocalDateTime.now());
        record.setFinishedAt(LocalDateTime.now());
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException ignored) {
            // Another DLQ delivery already recorded the terminal state.
        }
    }
}
