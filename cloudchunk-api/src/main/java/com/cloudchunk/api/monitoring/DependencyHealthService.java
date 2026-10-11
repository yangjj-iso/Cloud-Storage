package com.cloudchunk.api.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded, read-only admin diagnostics. Never returns hostnames, passwords, stack traces,
 * user data, or provider response bodies. TCP checks are *not* service health checks.
 */
@Service
public class DependencyHealthService {
    private static final int SOCKET_TIMEOUT_MS = 700;
    private static final String BACKLOG_SQL = """
        SELECT COUNT(*) FROM event_outbox
        WHERE status IN (0, 1, 3)
          AND created_at < NOW(3) - INTERVAL 10 MINUTE
        """;

    private final DataSource datasource;
    private final RedisConnectionFactory redis;
    private final String rmqNamesrv;
    private final String minioEndpoint;
    private final boolean aiChatEnabled;
    private final boolean aiEmbeddingEnabled;
    private final boolean qdrantEnabled;

    public record Component(String id, String status, String check, String note) {}
    public record Snapshot(String status, Instant sampledAt, List<Component> components,
                           Long oldOutboxEvents) {}

    public DependencyHealthService(
            DataSource datasource, RedisConnectionFactory redis,
            @Value("${rocketmq.name-server:127.0.0.1:9876}") String rmqNamesrv,
            @Value("${cloudchunk.storage.minio.endpoint:http://127.0.0.1:9002}") String minioEndpoint,
            @Value("${cloudchunk.ai.chat.enabled:false}") boolean aiChatEnabled,
            @Value("${cloudchunk.ai.embedding.enabled:false}") boolean aiEmbeddingEnabled,
            @Value("${cloudchunk.ai.qdrant.enabled:false}") boolean qdrantEnabled) {
        this.datasource = datasource;
        this.redis = redis;
        this.rmqNamesrv = rmqNamesrv;
        this.minioEndpoint = minioEndpoint;
        this.aiChatEnabled = aiChatEnabled;
        this.aiEmbeddingEnabled = aiEmbeddingEnabled;
        this.qdrantEnabled = qdrantEnabled;
    }

    public Snapshot snapshot() {
        Component mysql;
        Long oldOutbox = null;
        try (Connection connection = datasource.getConnection()) {
            if (!connection.isValid(2)) {
                mysql = new Component("mysql", "DOWN", "JDBC", "连接校验失败");
            } else {
                mysql = new Component("mysql", "UP", "JDBC", "连接有效");
                try (PreparedStatement statement = connection.prepareStatement(BACKLOG_SQL)) {
                    statement.setQueryTimeout(2);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) oldOutbox = result.getLong(1);
                    }
                } catch (Exception ignored) {
                    // Query failure is unknown, NEVER implicitly zero.
                }
            }
        } catch (Exception ignored) {
            mysql = new Component("mysql", "DOWN", "JDBC", "无法验证");
        }

        Component redisStatus;
        try (RedisConnection connection = redis.getConnection()) {
            String pong = connection.ping();
            redisStatus = new Component("redis", "PONG".equalsIgnoreCase(pong) ? "UP" : "DOWN",
                    "PING", "会话存储连接校验");
        } catch (Exception ignored) {
            redisStatus = new Component("redis", "DOWN", "PING", "无法验证");
        }

        Component rocketStatus = socketComponent("rocketmq", rmqNamesrv);
        Component objectStatus = uriComponent("minio", minioEndpoint);
        Component chatStatus = optional("ai-chat", aiChatEnabled);
        Component embeddingStatus = optional("ai-embedding", aiEmbeddingEnabled);
        Component vectorStatus = optional("qdrant", qdrantEnabled);

        List<Component> checks = List.of(mysql, redisStatus, rocketStatus, objectStatus,
                chatStatus, embeddingStatus, vectorStatus);
        // Do not assert overall UP when TCP-only dependencies are unverified.
        String status = mysql.status().equals("UP") && redisStatus.status().equals("UP")
                && oldOutbox != null && oldOutbox == 0 ? "PARTIALLY_VERIFIED" : "DEGRADED";
        return new Snapshot(status, Instant.now(), checks, oldOutbox);
    }

    private static Component optional(String id, boolean enabled) {
        return new Component(id, enabled ? "NOT_PROBED" : "DISABLED",
                "CONFIGURATION", enabled ? "已配置，未主动访问第三方服务" : "未启用");
    }

    private static Component uriComponent(String id, String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (uri.getHost() == null || uri.getPort() < -1) return notReachable(id);
            int port = uri.getPort() > 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            return socketComponent(id, uri.getHost(), port);
        } catch (IllegalArgumentException e) {
            return notReachable(id);
        }
    }

    private static Component socketComponent(String id, String endpoint) {
        // RocketMQ name-server format is a comma/semicolon-separated host:port set.
        String first = endpoint.split("[;,]", 2)[0].trim();
        int colon = first.lastIndexOf(':');
        if (colon <= 0 || colon == first.length() - 1) return notReachable(id);
        try {
            return socketComponent(id, first.substring(0, colon),
                    Integer.parseInt(first.substring(colon + 1)));
        } catch (NumberFormatException e) {
            return notReachable(id);
        }
    }

    private static Component socketComponent(String id, String host, int port) {
        if (host.isBlank() || port < 1 || port > 65535) return notReachable(id);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MS);
            return new Component(id, "REACHABLE_ONLY", "TCP", "仅端口可连接，未验证服务协议");
        } catch (Exception ignored) {
            return notReachable(id);
        }
    }

    private static Component notReachable(String id) {
        return new Component(id, "UNREACHABLE", "TCP", "连接失败或配置无效");
    }
}
