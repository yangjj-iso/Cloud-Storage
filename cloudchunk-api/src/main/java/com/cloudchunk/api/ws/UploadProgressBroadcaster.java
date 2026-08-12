package com.cloudchunk.api.ws;

import com.cloudchunk.common.constant.RedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 上传进度的发布入口，业务代码只跟它打交道。
 * <p>
 * 浏览器的 WebSocket 连接固定在某一个 API 节点，而分片确认的 HTTP 请求会被负载均衡分到任意节点，
 * 两者未必是同一台。所以进度先发到 Redis channel，由每个节点各自推送本地持有的连接。
 * 发布者自身也订阅该 channel，因此本地连接同样只会收到一次消息。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UploadProgressBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(UploadProgressBroadcaster.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final UploadProgressHandler handler;

    public UploadProgressBroadcaster(StringRedisTemplate redis,
                                     ObjectMapper mapper,
                                     UploadProgressHandler handler) {
        this.redis = redis;
        this.mapper = mapper;
        this.handler = handler;
    }

    /**
     * 广播一次上传进度。进度推送是尽力而为的辅助信号，前端另有本地进度兜底，
     * 因此这里的任何失败都只记录日志，不会影响分片上传本身的结果。
     */
    public void broadcast(String fileId, Object progress) {
        if (fileId == null || fileId.isBlank()) return;
        String json;
        try {
            json = mapper.writeValueAsString(progress);
        } catch (Exception e) {
            log.warn("ws progress serialize failed: fileId={}", fileId, e);
            return;
        }
        try {
            redis.convertAndSend(RedisKeys.CHANNEL_UPLOAD_PROGRESS,
                    mapper.writeValueAsString(new UploadProgressEnvelope(fileId, json)));
        } catch (Exception e) {
            // Redis 不可用时退回本地推送：单节点部署仍然完整可用，多节点则退化为可能丢推送。
            log.warn("ws progress fan-out failed, falling back to local delivery: fileId={}", fileId, e);
            handler.deliverLocal(fileId, json);
        }
    }
}
