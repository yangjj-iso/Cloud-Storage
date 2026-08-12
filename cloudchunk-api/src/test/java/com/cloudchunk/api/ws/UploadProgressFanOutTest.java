package com.cloudchunk.api.ws;

import com.cloudchunk.common.constant.RedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传进度跨节点扇出的行为验证。
 * <p>
 * 这条链路的失败模式是静默的：连接和分片确认请求落在不同节点时不会报错，只是进度推不到。
 * 因此这里覆盖完整回环——发布侧产出的信封必须能被订阅侧解析并正确路由。
 */
class UploadProgressFanOutTest {

    private static final String FILE_ID = "11111111111111111111111111111111";
    private static final String OTHER_FILE_ID = "22222222222222222222222222222222";

    private ObjectMapper mapper;
    private UploadProgressHandler handler;
    private StringRedisTemplate redis;
    private UploadProgressBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        handler = new UploadProgressHandler(mapper);
        redis = mock(StringRedisTemplate.class);
        broadcaster = new UploadProgressBroadcaster(redis, mapper, handler);
    }

    private WebSocketSession connect(String fileId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(UploadWsAuthInterceptor.ATTR_FILE_ID, fileId);
        when(session.getAttributes()).thenReturn(attrs);
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);
        return session;
    }

    /** 回环用例：发布侧序列化出来的东西，订阅侧必须能原样解出来。 */
    @Test
    void publishedEnvelopeIsDecodedAndRoutedToTheMatchingSession() throws Exception {
        WebSocketSession target = connect(FILE_ID);
        WebSocketSession unrelated = connect(OTHER_FILE_ID);

        broadcaster.broadcast(FILE_ID, Map.of("chunkIndex", 3, "allReady", false));

        String published = capturePublished();
        handler.onMessage(new DefaultMessage(
                RedisKeys.CHANNEL_UPLOAD_PROGRESS.getBytes(StandardCharsets.UTF_8),
                published.getBytes(StandardCharsets.UTF_8)), null);

        TextMessage delivered = captureSent(target);
        assertThat(delivered.getPayload()).contains("\"chunkIndex\":3");
        verify(unrelated, never()).sendMessage(any());
    }

    /** 本地直推会让其他节点收不到进度，所以正常路径必须走 Redis。 */
    @Test
    void broadcastGoesThroughRedisInsteadOfPushingLocally() throws Exception {
        WebSocketSession target = connect(FILE_ID);

        broadcaster.broadcast(FILE_ID, Map.of("chunkIndex", 1));

        verify(redis).convertAndSend(eq(RedisKeys.CHANNEL_UPLOAD_PROGRESS), anyString());
        verify(target, never()).sendMessage(any());
    }

    /** Redis 不可用时单节点部署仍应完整可用。 */
    @Test
    void fallsBackToLocalDeliveryWhenRedisIsUnavailable() throws Exception {
        WebSocketSession target = connect(FILE_ID);
        doThrow(new IllegalStateException("redis down"))
                .when(redis).convertAndSend(anyString(), anyString());

        broadcaster.broadcast(FILE_ID, Map.of("chunkIndex", 9));

        assertThat(captureSent(target).getPayload()).contains("\"chunkIndex\":9");
    }

    @Test
    void closedSessionStopsReceivingProgress() throws Exception {
        WebSocketSession target = connect(FILE_ID);
        handler.afterConnectionClosed(target, CloseStatus.NORMAL);

        handler.deliverLocal(FILE_ID, "{\"chunkIndex\":4}");

        verify(target, never()).sendMessage(any());
    }

    /**
     * 一个 fileId 上的重连不能影响另一条已存在的连接。
     * 注册与注销若不在同一把桶锁内完成，新连接可能被加进已从 registry 移除的孤儿集合。
     */
    @Test
    void reconnectKeepsReceivingAfterAnotherSessionOnSameFileClosed() throws Exception {
        WebSocketSession first = connect(FILE_ID);
        WebSocketSession second = connect(FILE_ID);
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);

        handler.deliverLocal(FILE_ID, "{\"chunkIndex\":6}");

        assertThat(captureSent(second).getPayload()).contains("\"chunkIndex\":6");
        verify(first, never()).sendMessage(any());
    }

    @Test
    void malformedEnvelopeIsIgnoredInsteadOfKillingTheSubscription() {
        org.assertj.core.api.Assertions.assertThatCode(() ->
                handler.onMessage(new DefaultMessage(
                        RedisKeys.CHANNEL_UPLOAD_PROGRESS.getBytes(StandardCharsets.UTF_8),
                        "not json".getBytes(StandardCharsets.UTF_8)), null))
                .doesNotThrowAnyException();
    }

    private String capturePublished() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(RedisKeys.CHANNEL_UPLOAD_PROGRESS), captor.capture());
        return captor.getValue();
    }

    private TextMessage captureSent(WebSocketSession session) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        return captor.getValue();
    }
}
