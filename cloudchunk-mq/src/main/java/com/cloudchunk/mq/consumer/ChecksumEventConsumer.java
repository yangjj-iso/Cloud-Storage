package com.cloudchunk.mq.consumer;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.contract.event.ChecksumRequested;
import com.cloudchunk.core.upload.service.ChecksumService;
import com.cloudchunk.infra.inbox.InboxService;
import com.cloudchunk.infra.tracing.IntegrationEventTracing;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cloudchunk.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = MqTopics.CHECKSUM,
        consumerGroup = MqTopics.CG_CHECKSUM,
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class ChecksumEventConsumer implements RocketMQListener<ChecksumRequested> {

    private static final Logger log = LoggerFactory.getLogger(ChecksumEventConsumer.class);
    private final ChecksumService service;
    private final InboxService inboxService;
    private final IntegrationEventTracing eventTracing;

    public ChecksumEventConsumer(ChecksumService service,
                                 InboxService inboxService,
                                 IntegrationEventTracing eventTracing) {
        this.service = service;
        this.inboxService = inboxService;
        this.eventTracing = eventTracing;
    }

    @Override
    public void onMessage(ChecksumRequested event) {
        try (var trace = eventTracing.startConsumer(event, "checksum verify")) {
            String eventId = event.getEventId() == null || event.getEventId().isBlank()
                    ? "legacy-checksum-" + event.getFileId() : event.getEventId();
            InboxService.AcquireResult acquired = inboxService.acquire(eventId, "ChecksumRequested");
            if (acquired == InboxService.AcquireResult.ALREADY_DONE) return;
            if (acquired == InboxService.AcquireResult.BUSY) {
                throw new IllegalStateException("checksum event is already being processed: " + eventId);
            }
            try {
                service.verify(event);
                inboxService.markDone(eventId);
                log.info("checksum processed: fileId={}, eventId={}", event.getFileId(), eventId);
            } catch (Exception e) {
                trace.error(e);
                inboxService.markFailed(eventId, e);
                throw e;
            }
        }
    }
}
