package com.cloudchunk.api.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DependencyHealthServiceTest {
    @Test
    void reportsNoCredentialsOrAddressesAndClearlyLabelsOnlyTcpConnectivity() throws Exception {
        DataSource database = mock(DataSource.class);
        Connection jdbc = mock(Connection.class);
        PreparedStatement query = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(database.getConnection()).thenReturn(jdbc);
        when(jdbc.isValid(2)).thenReturn(true);
        when(jdbc.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getLong(1)).thenReturn(0L);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection redis = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(redis);
        when(redis.ping()).thenReturn("PONG");

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            DependencyHealthService monitor = new DependencyHealthService(
                    database, factory, "127.0.0.1:" + port, "http://127.0.0.1:" + port,
                    true, false, true);
            var sample = monitor.snapshot();
            assertThat(sample.status()).isEqualTo("PARTIALLY_VERIFIED");
            assertThat(sample.oldOutboxEvents()).isZero();
            assertThat(sample.components()).extracting(DependencyHealthService.Component::status)
                    .contains("UP", "REACHABLE_ONLY", "NOT_PROBED", "DISABLED");
            assertThat(sample.toString()).doesNotContain("127.0.0.1")
                    .doesNotContain("api-key").doesNotContain("password");
            verify(jdbc).close();
            verify(redis).close();
        }
    }

    @Test
    void outageFailsClosedAndUnknownBacklogIsNeverZero() throws Exception {
        DataSource database = mock(DataSource.class);
        when(database.getConnection()).thenThrow(new java.sql.SQLException("sensitive-db-credential"));
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new IllegalStateException("redis-secret"));
        DependencyHealthService monitor = new DependencyHealthService(
                database, factory, "127.0.0.1:0", "invalid-uri", false, false, false);
        var sample = monitor.snapshot();
        assertThat(sample.status()).isEqualTo("DEGRADED");
        assertThat(sample.oldOutboxEvents()).isNull();
        assertThat(sample.components()).extracting(DependencyHealthService.Component::status)
                .contains("DOWN", "UNREACHABLE", "DISABLED");
        assertThat(sample.toString()).doesNotContain("sensitive-db-credential", "redis-secret");
    }
}
