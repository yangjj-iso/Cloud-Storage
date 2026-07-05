package com.cloudchunk.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
 * 前端连接 ws://host/ws/upload/{fileId} 后，服务端在分片上传 / 确认时
 * 调用 {@link #broadcast} 向该 fileId 的所有订阅者推送进度 JSON。
 */
@Component
public class UploadProgressHandler extends TextWebSocketHandler implements SubProtocolCapable {

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
        registry.computeIfAbsent(fileId, k -> new CopyOnWriteArraySet<>()).add(session);
        log.debug("ws connected: fileId={}, sessionId={}", fileId, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String fileId = authenticatedFileId(session);
        if (fileId == null) return;
        Set<WebSocketSession> set = registry.get(fileId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) registry.remove(fileId);
        }
    }

    /**
     * 向订阅了指定 fileId 的所有 WebSocket 客户端广播进度消息。
     *
     * @param fileId   上传会话 ID
     * @param progress 进度数据（会被序列化为 JSON）
     */
    public void broadcast(String fileId, Object progress) {
        Set<WebSocketSession> sessions = registry.get(fileId);
        if (sessions == null || sessions.isEmpty()) return;
        try {
            String json = mapper.writeValueAsString(progress);
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
        } catch (Exception e) {
            log.warn("ws broadcast error: fileId={}", fileId, e);
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
