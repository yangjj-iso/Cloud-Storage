package com.cloudchunk.core.upload.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.mapper.FileMetaMapper;
import com.cloudchunk.mq.message.ChecksumMessage;
import com.cloudchunk.mq.producer.ChecksumProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 合并 -> 校验一致性补偿：合并成功后如果 checksum 消息投递失败或丢失，
 * 文件会停留在 MERGED。读路径只放行 AVAILABLE，因此需要定时补投校验消息。
 */
@Component
public class ChecksumCompensationTask {

    private static final Logger log = LoggerFactory.getLogger(ChecksumCompensationTask.class);

    private static final int BATCH_SIZE = 50;
    private static final int STALE_MINUTES = 5;

    private final FileMetaMapper fileMetaMapper;
    private final ChecksumProducer checksumProducer;

    public ChecksumCompensationTask(FileMetaMapper fileMetaMapper, ChecksumProducer checksumProducer) {
        this.fileMetaMapper = fileMetaMapper;
        this.checksumProducer = checksumProducer;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void compensate() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_MINUTES);
        List<FileMeta> pending = fileMetaMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getStatus, FileStatus.MERGED)
                .isNull(FileMeta::getDeletedAt)
                .lt(FileMeta::getCreatedAt, threshold)
                .orderByAsc(FileMeta::getCreatedAt)
                .last("limit " + BATCH_SIZE));
        if (pending.isEmpty()) {
            return;
        }

        int published = 0;
        for (FileMeta m : pending) {
            try {
                ChecksumMessage msg = new ChecksumMessage();
                msg.setFileId(m.getFileId());
                msg.setBucket(m.getBucket());
                msg.setObjectKey(m.getObjectKey());
                msg.setExpectMd5(m.getFileMd5());
                msg.setMimeType(m.getMimeType());
                msg.setFileSize(m.getFileSize() == null ? 0L : m.getFileSize());
                checksumProducer.publish(msg);
                published++;
            } catch (Exception e) {
                log.warn("checksum compensation publish failed: fileId={}", m.getFileId(), e);
            }
        }
        log.info("checksum compensation done: {}/{} published", published, pending.size());
    }
}
