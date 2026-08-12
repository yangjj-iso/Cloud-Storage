package com.cloudchunk.api.ws;

import com.cloudchunk.common.constant.RedisKeys;
import com.cloudchunk.core.CloudchunkProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.List;

/**
 * WebSocket 配置：前端通过 ws://host/ws/upload/{fileId} 订阅实时上传进度。
 */
@Configuration
@EnableWebSocket
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebSocketConfig implements WebSocketConfigurer {

    private final UploadProgressHandler handler;
    private final UploadWsAuthInterceptor authInterceptor;
    private final CloudchunkProperties properties;

    public WebSocketConfig(UploadProgressHandler handler,
                           UploadWsAuthInterceptor authInterceptor,
                           CloudchunkProperties properties) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
        this.properties = properties;
    }

    /**
     * 订阅上传进度 channel，使本节点能推送由其他节点产生的进度。
     * 缺了这个容器，多节点部署下 WebSocket 会静默地收不到进度。
     */
    @Bean
    public RedisMessageListenerContainer uploadProgressListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(handler, new ChannelTopic(RedisKeys.CHANNEL_UPLOAD_PROGRESS));
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> configured = properties.getWebSocket() == null
                || properties.getWebSocket().getAllowedOrigins() == null
                ? List.of()
                : properties.getWebSocket().getAllowedOrigins();
        List<String> origins = configured.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(String::trim)
                .toList();
        registry.addHandler(handler, "/ws/upload/{fileId}")
                .addInterceptors(authInterceptor)
                .setAllowedOrigins(origins.toArray(String[]::new));
    }
}
