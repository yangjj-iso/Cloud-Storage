package com.cloudchunk.api.ws;

import com.cloudchunk.core.CloudchunkProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.List;

/**
 * WebSocket 配置：前端通过 ws://host/ws/upload/{fileId} 订阅实时上传进度。
 */
@Configuration
@EnableWebSocket
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
