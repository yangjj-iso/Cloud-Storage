package com.cloudchunk.mq.outbox;

import com.cloudchunk.contract.event.BlobBroken;
import com.cloudchunk.contract.event.ChecksumRequested;
import com.cloudchunk.contract.event.FileAvailable;
import com.cloudchunk.contract.event.IntegrationEvent;
import com.cloudchunk.contract.event.TranscodeRequested;
import com.cloudchunk.infra.outbox.OutboxEvent;
import com.cloudchunk.infra.outbox.OutboxEventMapper;
import com.cloudchunk.infra.tracing.IntegrationEventTracing;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "cloudchunk.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-"
            + UUID.randomUUID().toString().substring(0, 8);

    private final OutboxEventMapper mapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final IntegrationEventTracing eventTracing;
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(OutboxEventMapper mapper,
                           RocketMQTemplate rocketMQTemplate,
                           ObjectMapper objectMapper,
                           IntegrationEventTracing eventTracing,
                           MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
        this.eventTracing = eventTracing;
        this.published = Counter.builder("cloudchunk.outbox.published")
                .description("Outbox events acknowledged by RocketMQ")
                .register(meterRegistry);
        this.failed = Counter.builder("cloudchunk.outbox.publish_failures")
                .description("Outbox publish attempts scheduled for retry")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${cloudchunk.outbox.poll-interval-ms:1000}", initialDelay = 5000)
    public void publishReady() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusMinutes(10);
        List<OutboxEvent> rows = mapper.findReady(now, staleBefore, BATCH_SIZE);
        for (OutboxEvent row : rows) {
            if (mapper.claim(row.getId(), workerId, now, staleBefore) <= 0) continue;
            try {
                IntegrationEvent event = deserialize(row);
                try (var trace = eventTracing.startProducer(event, row.getDestination())) {
                    try {
                        Message<IntegrationEvent> message = MessageBuilder.withPayload(event)
                                .setHeader(MessageConst.PROPERTY_KEYS, row.getAggregateKey())
                                .setHeader("eventId", row.getEventId())
                                .setHeader("schemaVersion", row.getSchemaVersion())
                                .build();
                        SendResult result = rocketMQTemplate.syncSend(row.getDestination(), message, 3000L);
                        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                            throw new IllegalStateException("RocketMQ did not acknowledge SEND_OK: "
                                    + (result == null ? "null" : result.getSendStatus()));
                        }
                        mapper.markPublished(row.getId(), workerId, LocalDateTime.now());
                        published.increment();
                    } catch (Exception e) {
                        trace.error(e);
                        throw e;
                    }
                }
            } catch (Exception e) {
                failed.increment();
                int attempt = row.getAttemptCount() == null ? 1 : row.getAttemptCount() + 1;
                long delay = Math.min(3600, 5L << Math.min(attempt, 9));
                // Never persist/log broker exception messages: they may embed URLs or credentials.
                // The original event stays in the outbox for durable retry.
                String category = e instanceof IllegalArgumentException ? "EVENT_INVALID" : "SEND_FAILED";
                mapper.markRetry(row.getId(), workerId, LocalDateTime.now().plusSeconds(delay), category);
                log.warn("outbox publish retry scheduled: type={}, attempt={}",
                        category, attempt);
            }
        }
    }

    private IntegrationEvent deserialize(OutboxEvent row) throws Exception {
        Class<? extends IntegrationEvent> type = switch (row.getEventType()) {
            case "ChecksumRequested" -> ChecksumRequested.class;
            case "TranscodeRequested" -> TranscodeRequested.class;
            case "BlobBroken" -> BlobBroken.class;
            case "FileAvailable" -> FileAvailable.class;
            default -> throw new IllegalArgumentException("unsupported outbox event: " + row.getEventType());
        };
        return objectMapper.readValue(row.getPayload(), type);
    }
}
