package com.cloudchunk.transcode.consumer;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.transcode.entity.TranscodeRecord;
import com.cloudchunk.core.transcode.mapper.TranscodeRecordMapper;
import com.cloudchunk.mq.message.TranscodeMessage;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 转码死信队列消费者：处理超过最大重试次数仍失败的转码任务。
 * RocketMQ 默认重试 16 次后投递到 %DLQ% topic。
 * 此消费者负责：标记文件转码状态为 FAILED、记录错误信息、输出告警日志。
 */
@Component
@RocketMQMessageListener(
        topic = MqTopics.DLQ_TRANSCODE_IMG,
        consumerGroup = MqTopics.CG_TRANSCODE_DLQ,
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class TranscodeDlqConsumer implements RocketMQListener<TranscodeMessage> {

    private static final Logger log = LoggerFactory.getLogger(TranscodeDlqConsumer.class);

    private final FileMetaService fileMetaService;
    private final TranscodeRecordMapper recordMapper;

    public TranscodeDlqConsumer(FileMetaService fileMetaService,
                                TranscodeRecordMapper recordMapper) {
        this.fileMetaService = fileMetaService;
        this.recordMapper = recordMapper;
    }

    @Override
    public void onMessage(TranscodeMessage msg) {
        log.error("[TRANSCODE-DLQ-ALERT] fileId={}, mimeType={}, retryCount={}, " +
                        "producedAt={}. Message exhausted all retries and entered dead letter queue.",
                msg.getFileId(), msg.getMimeType(), msg.getRetryCount(), msg.getProducedAt());

        try {
            fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.FAILED);
        } catch (Exception e) {
            log.error("DLQ: failed to update transcode status for fileId={}", msg.getFileId(), e);
        }

        try {
            TranscodeRecord record = new TranscodeRecord();
            record.setFileId(msg.getFileId());
            record.setTaskType("dlq");
            record.setStatus(3);
            record.setRetryCount(msg.getRetryCount());
            record.setErrorMsg("Exhausted all retries, moved to DLQ");
            record.setStartedAt(LocalDateTime.now());
            record.setFinishedAt(LocalDateTime.now());
            recordMapper.insert(record);
        } catch (Exception e) {
            log.error("DLQ: failed to insert transcode record for fileId={}", msg.getFileId(), e);
        }
    }
}
