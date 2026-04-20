package com.cloudchunk.mq.producer;

import com.cloudchunk.common.constant.MqTopics;
import com.cloudchunk.common.constant.RedisKeys;
import com.cloudchunk.common.trace.TraceContext;
import com.cloudchunk.common.util.IdUtils;
import com.cloudchunk.common.util.MimeUtils;
import com.cloudchunk.infra.redis.RedisService;
import com.cloudchunk.mq.message.TranscodeMessage;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 发送转码任务消息到 RocketMQ。
 * 通过 Redis SETNX 保证同一 fileId 短时间内不重复投递。
 */
@Component
public class TranscodeProducer {

    private static final Logger log = LoggerFactory.getLogger(TranscodeProducer.class);

    private final RocketMQTemplate rocketMQTemplate;
    private final RedisService redisService;

    public TranscodeProducer(RocketMQTemplate rocketMQTemplate, RedisService redisService) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.redisService = redisService;
    }

    /**
     * @return true 表示投递成功；false 表示重复投递被幂等拦截或类型无需转码
     */
    public boolean publish(TranscodeMessage msg) {
        String tag = MimeUtils.tag(msg.getMimeType());
        if (tag == null) {
            log.info("skip transcode, unsupported mime: fileId={}, mime={}", msg.getFileId(), msg.getMimeType());
            return false;
        }
        String sentKey = RedisKeys.transcodeSent(msg.getFileId());
        Boolean first = redisService.setIfAbsent(sentKey, "1", Duration.ofMinutes(5));
        if (!Boolean.TRUE.equals(first)) {
            log.debug("skip duplicated transcode publish: {}", msg.getFileId());
            return false;
        }

        if (msg.getMsgId() == null) msg.setMsgId(IdUtils.uuid32());
        if (msg.getProducedAt() == null) msg.setProducedAt(Instant.now());
        if (msg.getTraceId() == null) msg.setTraceId(TraceContext.get());

        Message<TranscodeMessage> message = MessageBuilder.withPayload(msg)
                .setHeader(MessageConst.PROPERTY_KEYS, msg.getFileId())
                .setHeader(MessageConst.PROPERTY_TAGS, tag)
                .build();

        try {
            rocketMQTemplate.syncSend(MqTopics.transcodeDestination(tag), message, 3000L);
            log.info("transcode published: fileId={}, tag={}", msg.getFileId(), tag);
            return true;
        } catch (Exception e) {
            // 发送失败清理幂等 key，允许下次重试
            redisService.delete(sentKey);
            log.error("transcode publish failed: fileId={}, tag={}", msg.getFileId(), tag, e);
            throw e;
        }
    }
}
