package com.cloudchunk.core.upload.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.contract.event.ChecksumRequested;
import com.cloudchunk.contract.event.IntegrationEventPublisher;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.mapper.FileMetaMapper;
import com.cloudchunk.common.util.Md5Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 合并 -> 校验一致性补偿：合并成功后如果 checksum 消息投递失败或丢失，
 * 文件会停留在 MERGED。读路径只放行 AVAILABLE，因此需要定时补投校验消息。
 */
@Component
@ConditionalOnProperty(name = "cloudchunk.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ChecksumCompensationTask {

    private static final Logger log = LoggerFactory.getLogger(ChecksumCompensationTask.class);

    private static final int BATCH_SIZE = 50;
    private static final int STALE_MINUTES = 5;
    /** 与 @Scheduled 的 fixedDelay 保持一致，同时用作补偿事件的去重时间窗。 */
    private static final long PERIOD_MS = 300_000L;

    private final FileMetaMapper fileMetaMapper;
    private final IntegrationEventPublisher eventPublisher;

    public ChecksumCompensationTask(FileMetaMapper fileMetaMapper, IntegrationEventPublisher eventPublisher) {
        this.fileMetaMapper = fileMetaMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 按「文件 + 时间窗」派生确定性事件 ID，而不是让发布器生成随机 UUID。
     * <p>
     * 每个 Worker 都会跑这个定时任务，并扫到同一批仍停在 MERGED 的文件。用随机 ID 时
     * event_inbox 无法判断它们是同一件事，校验消费者就会对同一个文件重复地整体回源计算 MD5：
     * 一个 10GB 文件在 3 个 Worker 下每 5 分钟就是 30GB 的对象存储读取，而且只要文件still
     * 停在 MERGED 就会一直放大下去。
     * <p>
     * 时间窗取值等于调度周期，因此每个 Worker 在每个窗口内恰好跑一次、算出同一个 ID，
     * {@code uk_outbox_event_id} 会把它们收敛成一条记录。窗口滚动后 ID 变化，
     * 所以真正需要的重试不会被永久堵死——这也是不能用「完全固定」ID 的原因：
     * 已发布的 outbox 行不会被清理，固定 ID 会让补偿对每个文件一生只触发一次。
     * <p>
     * 长度必须是 32：{@code event_outbox.event_id} 是 CHAR(32)。
     */
    private static String compensationEventId(String fileId) {
        long window = System.currentTimeMillis() / PERIOD_MS;
        return Md5Utils.md5(("checksum-compensation:" + fileId + ":" + window)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Scheduled(fixedDelay = PERIOD_MS, initialDelay = 120_000)
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
        int deduped = 0;
        for (FileMeta m : pending) {
            try {
                ChecksumRequested msg = new ChecksumRequested();
                msg.setEventId(compensationEventId(m.getFileId()));
                msg.setFileId(m.getFileId());
                msg.setBucket(m.getBucket());
                msg.setObjectKey(m.getObjectKey());
                msg.setExpectMd5(m.getFileMd5());
                msg.setMimeType(m.getMimeType());
                msg.setFileSize(m.getFileSize() == null ? 0L : m.getFileSize());
                eventPublisher.enqueue(MqTopics.CHECKSUM, m.getFileId(), msg);
                published++;
            } catch (DuplicateKeyException alreadyQueued) {
                // 本窗口内已有其他 Worker 投递过同一个补偿事件，属于预期而非故障。
                deduped++;
            } catch (Exception e) {
                log.warn("checksum compensation publish failed: fileId={}", m.getFileId(), e);
            }
        }
        log.info("checksum compensation done: {}/{} published, {} already queued by peers",
                published, pending.size(), deduped);
    }
}
