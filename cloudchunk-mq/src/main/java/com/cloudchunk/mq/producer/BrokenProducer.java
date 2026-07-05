package com.cloudchunk.mq.producer;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.common.trace.TraceContext;
import com.cloudchunk.mq.message.BrokenMessage;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class BrokenProducer {

    private static final Logger log = LoggerFactory.getLogger(BrokenProducer.class);

    private final RocketMQTemplate rocketMQTemplate;

    public BrokenProducer(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @CircuitBreaker(name = "mq")
    @Retry(name = "mq")
    public void publish(BrokenMessage msg) {
        if (msg.getTraceId() == null) msg.setTraceId(TraceContext.get());
        Message<BrokenMessage> message = MessageBuilder.withPayload(msg)
                .setHeader(MessageConst.PROPERTY_KEYS, msg.getFileId())
                .build();
        rocketMQTemplate.syncSend(MqTopics.BROKEN, message, 3000L);
        log.warn("file broken notified: fileId={}, reason={}", msg.getFileId(), msg.getReason());
    }
}
