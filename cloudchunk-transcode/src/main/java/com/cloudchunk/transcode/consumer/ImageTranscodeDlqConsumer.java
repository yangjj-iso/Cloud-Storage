package com.cloudchunk.transcode.consumer;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.contract.event.TranscodeRequested;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cloudchunk.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(topic = MqTopics.DLQ_TRANSCODE_IMG,
        consumerGroup = MqTopics.CG_TRANSCODE_IMG_DLQ, consumeMode = ConsumeMode.CONCURRENTLY)
public class ImageTranscodeDlqConsumer implements RocketMQListener<TranscodeRequested> {
    private final TranscodeDlqHandler handler;
    public ImageTranscodeDlqConsumer(TranscodeDlqHandler handler) { this.handler = handler; }
    @Override public void onMessage(TranscodeRequested event) { handler.handle(event, "image"); }
}
