package com.cloudchunk.core.upload.consumer;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.common.trace.TraceContext;
import com.cloudchunk.core.upload.service.ChecksumService;
import com.cloudchunk.mq.message.ChecksumMessage;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RocketMQMessageListener(
        topic = MqTopics.CHECKSUM,
        consumerGroup = MqTopics.CG_CHECKSUM,
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class ChecksumConsumer implements RocketMQListener<ChecksumMessage> {

    private static final Logger log = LoggerFactory.getLogger(ChecksumConsumer.class);

    private final ChecksumService service;

    public ChecksumConsumer(ChecksumService service) {
        this.service = service;
    }

    @Override
    public void onMessage(ChecksumMessage msg) {
        TraceContext.set(msg.getTraceId());
        try {
            service.verify(msg);
            log.info("checksum ok: fileId={}", msg.getFileId());
        } catch (Exception e) {
            log.error("checksum failed, will retry: fileId={}", msg.getFileId(), e);
            throw e;
        } finally {
            TraceContext.clear();
        }
    }
}
