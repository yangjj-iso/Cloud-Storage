package com.cloudchunk.api.ws;

import com.cloudchunk.common.constant.RedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 上传进度 WebSocket 推送处理器。
 * <p>
 * 前端连接 ws://host/ws/upload/{fileId} 后由本节点持有该连接。进度不是由业务代码直接调进来的，
 * 而是订阅 {@link RedisKeys#CHANNEL_UPLOAD_PROGRESS} 得到：分片确认请求可能落在任意 API 节点，
 * 只有经过 Pub/Sub 扇出，持有连接的那个节点才能拿到进度。
 * 发布侧见 {@link UploadProgressBroadcaster}。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UploadProgressHandler extends TextWebSocketHandler
        implements SubProtocolCapable, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(UploadProgressHandler.class);
    private static final List<String> SUB_PROTOCOLS = List.of("cloudchunk-upload");

    /** fileId → 连接集合 */
    private final Map<String, Set<WebSocketSession>> registry = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public UploadProgressHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<String> getSubProtocols() {
        return SUB_PROTOCOLS;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String fileId = authenticatedFileId(session);
        if (fileId == null) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException ignored) {}
            return;
        }
        // 注册与注销都必须在 map 的同一把桶锁内完成集合的增删。
        // 若写成 computeIfAbsent(...).add(session)，add 会落在锁外：并发的断开可能先看到空集合
        // 并移除整个映射，随后这次 add 就加进了一个已经脱离 registry 的孤儿集合，
        // 表现为新连接静默收不到任何进度。重连频繁时这条竞态是真实可达的。
        registry.compute(fileId, (k, existing) -> {
            Set<WebSocketSession> sessions =
                    existing == null ? new CopyOnWriteArraySet<>() : existing;
            sessions.add(session);
            return sessions;
        });
        log.debug("ws connected: fileId={}, sessionId={}", fileId, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String fileId = authenticatedFileId(session);
        if (fileId == null) return;
        registry.computeIfPresent(fileId, (k, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /** 收到扇出消息：只有本节点持有该 fileId 的连接时才会真正推送。 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            UploadProgressEnvelope envelope =
                    mapper.readValue(message.getBody(), UploadProgressEnvelope.class);
            deliverLocal(envelope.fileId(), envelope.payload());
        } catch (Exception e) {
            log.warn("ws progress envelope decode failed", e);
        }
    }

    /**
     * 把进度 JSON 推给本节点上订阅该 fileId 的连接。
     * <p>
     * 业务代码不应直接调用这里，否则进度只会到达恰好持有连接的那个节点。
     * 正常路径是 {@link UploadProgressBroadcaster#broadcast}，仅在 Pub/Sub 发布失败时降级直调。
     *
     * @param fileId 上传会话 ID
     * @param json   已序列化的进度 JSON
     */
    void deliverLocal(String fileId, String json) {
        Set<WebSocketSession> sessions = registry.get(fileId);
        if (sessions == null || sessions.isEmpty()) return;
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : sessions) {
            if (s.isOpen()) {
                try {
                    s.sendMessage(msg);
                } catch (IOException e) {
                    log.debug("ws send failed: {}", s.getId(), e);
                }
            }
        }
    }

    private String authenticatedFileId(WebSocketSession session) {
        Object fileId = session.getAttributes().get(UploadWsAuthInterceptor.ATTR_FILE_ID);
        if (fileId instanceof String value && !value.isBlank()) {
            return value;
        }
        return null;
    }
}
