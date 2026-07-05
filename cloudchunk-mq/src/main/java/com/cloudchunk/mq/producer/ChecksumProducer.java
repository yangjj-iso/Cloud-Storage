package com.cloudchunk.mq.producer;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.common.trace.TraceContext;
import com.cloudchunk.mq.message.ChecksumMessage;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ChecksumProducer {

    private static final Logger log = LoggerFactory.getLogger(ChecksumProducer.class);

    private final RocketMQTemplate rocketMQTemplate;

    public ChecksumProducer(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @CircuitBreaker(name = "mq")
    @Retry(name = "mq")
    public void publish(ChecksumMessage msg) {
        if (msg.getProducedAt() == null) msg.setProducedAt(Instant.now());
        if (msg.getTraceId() == null) msg.setTraceId(TraceContext.get());

        Message<ChecksumMessage> message = MessageBuilder.withPayload(msg)
                .setHeader(MessageConst.PROPERTY_KEYS, msg.getFileId())
                .build();

        rocketMQTemplate.syncSend(MqTopics.CHECKSUM, message, 3000L);
        log.info("checksum published: fileId={}", msg.getFileId());
    }
}
