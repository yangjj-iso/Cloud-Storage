package com.cloudchunk.mq.consumer;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.contract.event.BlobBroken;
import com.cloudchunk.infra.inbox.InboxService;
import com.cloudchunk.infra.tracing.IntegrationEventTracing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 消费整文件校验失败事件。
 * <p>
 * 状态流转和对象回收在发布端的同一个事务里已经完成（file_meta 置为 BROKEN、写入 object_gc_task），
 * 所以这里只负责把损坏事实变成可告警信号：结构化日志 + Prometheus 计数器。
 * 没有消费者时这些事件会一直堆在 topic 里，运维侧看不到任何损坏迹象。
 */
@Component
@ConditionalOnProperty(name = "cloudchunk.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = MqTopics.BROKEN,
        consumerGroup = MqTopics.CG_BROKEN_NOTIFY,
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class BrokenBlobConsumer implements RocketMQListener<BlobBroken> {

    private static final Logger log = LoggerFactory.getLogger(BrokenBlobConsumer.class);
    private static final String METRIC = "cloudchunk.blob.broken";

    private final InboxService inboxService;
    private final IntegrationEventTracing eventTracing;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public BrokenBlobConsumer(InboxService inboxService,
                              IntegrationEventTracing eventTracing,
                              ObjectProvider<MeterRegistry> meterRegistry) {
        this.inboxService = inboxService;
        this.eventTracing = eventTracing;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void onMessage(BlobBroken event) {
        try (var trace = eventTracing.startConsumer(event, "blob broken notify")) {
            String eventId = event.getEventId() == null || event.getEventId().isBlank()
                    ? "legacy-broken-" + event.getFileId() : event.getEventId();
            InboxService.AcquireResult acquired = inboxService.acquire(eventId, "BlobBroken");
            if (acquired == InboxService.AcquireResult.ALREADY_DONE) return;
            if (acquired == InboxService.AcquireResult.BUSY) {
                throw new IllegalStateException("broken event is already being processed: " + eventId);
            }
            try {
                log.error("blob checksum failed, file marked BROKEN: fileId={}, reason={}, eventId={}",
                        event.getFileId(), event.getReason(), eventId);
                MeterRegistry registry = meterRegistry.getIfAvailable();
                if (registry != null) {
                    Counter.builder(METRIC)
                            .description("Files that failed whole-file checksum after merge")
                            .tag("reason", reasonTag(event.getReason()))
                            .register(registry)
                            .increment();
                }
                inboxService.markDone(eventId);
            } catch (Exception e) {
                trace.error(e);
                inboxService.markFailed(eventId, e);
                throw e;
            }
        }
    }

    /** 原始 reason 里可能带 MD5 等高基数内容，作为指标标签会撑爆时间序列。 */
    private static String reasonTag(String reason) {
        if (reason == null || reason.isBlank()) return "unknown";
        String lower = reason.toLowerCase();
        if (lower.contains("mismatch")) return "md5-mismatch";
        if (lower.contains("size")) return "size-mismatch";
        if (lower.contains("not found") || lower.contains("missing")) return "object-missing";
        return "other";
    }
}
