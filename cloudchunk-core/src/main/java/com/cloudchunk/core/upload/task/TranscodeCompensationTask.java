package com.cloudchunk.core.upload.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.mapper.FileMetaMapper;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.mq.message.TranscodeMessage;
import com.cloudchunk.mq.producer.TranscodeProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 合并→转码一致性补偿：定时扫描 file_meta 中 status=AVAILABLE 但 transcode_status=NONE 的记录，
 * 重新投递转码消息。这覆盖了合并成功后 MQ 发送失败导致的"遗漏转码"场景。
 */
@Component
public class TranscodeCompensationTask {

    private static final Logger log = LoggerFactory.getLogger(TranscodeCompensationTask.class);

    private static final int BATCH_SIZE = 50;
    private static final int STALE_MINUTES = 10;

    private final FileMetaMapper fileMetaMapper;
    private final FileMetaService fileMetaService;
    private final TranscodeProducer transcodeProducer;

    public TranscodeCompensationTask(FileMetaMapper fileMetaMapper,
                                     FileMetaService fileMetaService,
                                     TranscodeProducer transcodeProducer) {
        this.fileMetaMapper = fileMetaMapper;
        this.fileMetaService = fileMetaService;
        this.transcodeProducer = transcodeProducer;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void compensate() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_MINUTES);
        List<FileMeta> orphans = fileMetaMapper.selectList(
                new LambdaQueryWrapper<FileMeta>()
                        .eq(FileMeta::getStatus, FileStatus.AVAILABLE)
                        .eq(FileMeta::getTranscodeStatus, TranscodeStatus.NONE)
                        .lt(FileMeta::getCreatedAt, threshold)
                        .isNull(FileMeta::getDeletedAt)
                        .last("limit " + BATCH_SIZE));

        if (orphans.isEmpty()) return;
        log.info("transcode compensation: found {} orphan files", orphans.size());

        int published = 0;
        int skipped = 0;
        for (FileMeta m : orphans) {
            try {
                TranscodeMessage msg = new TranscodeMessage();
                msg.setFileId(m.getFileId());
                msg.setMimeType(m.getMimeType());
                msg.setBucket(m.getBucket());
                msg.setObjectKey(m.getObjectKey());
                msg.setFileSize(m.getFileSize());
                msg.setExpectMd5(m.getFileMd5());
                msg.setProducedAt(Instant.now());
                if (transcodeProducer.publish(msg)) {
                    published++;
                } else {
                    fileMetaService.updateTranscodeStatus(m.getFileId(), TranscodeStatus.SKIP);
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("compensation publish failed: fileId={}", m.getFileId(), e);
            }
        }
        log.info("transcode compensation done: {}/{} published, {} skipped (unsupported mime)",
                published, orphans.size(), skipped);
    }
}
