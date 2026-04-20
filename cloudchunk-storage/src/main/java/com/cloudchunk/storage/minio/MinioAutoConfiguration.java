package com.cloudchunk.storage.minio;

import com.cloudchunk.storage.StorageProperties;
import com.cloudchunk.storage.StorageStrategy;
import io.minio.MinioClient;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@ConditionalOnProperty(prefix = "cloudchunk.storage", name = "type", havingValue = "minio", matchIfMissing = true)
public class MinioAutoConfiguration {

    /**
     * 自定义 OkHttp 客户端，解决两类并发瓶颈：
     * <ol>
     *   <li><b>连接池</b>：默认 {@code maxIdleConnections=5 / keepAlive=5min}，高并发分片
     *       上传时连接频繁创建销毁，实测 TTFB 抖动很大。这里放大到 256，降低握手开销。</li>
     *   <li><b>派发器</b>：默认 {@code maxRequests=64 / maxRequestsPerHost=5}，单机 MinIO
     *       场景下 5 的 per-host 限制会直接把并发压回 5，必须调大。</li>
     * </ol>
     *
     * 同时调大读写超时：分片可能几十 MB，慢网络下默认 10s 秒超时会误报。
     */
    @Bean
    public OkHttpClient minioHttpClient() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(512);
        dispatcher.setMaxRequestsPerHost(256);
        return new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(256, 5, TimeUnit.MINUTES))
                .dispatcher(dispatcher)
                .protocols(List.of(Protocol.HTTP_1_1)) // MinIO 未开 h2 时避免协商失败
                .connectTimeout(Duration.ofSeconds(5))
                .writeTimeout(Duration.ofMinutes(10))
                .readTimeout(Duration.ofMinutes(10))
                .retryOnConnectionFailure(true)
                .build();
    }

    @Bean(destroyMethod = "close")
    public MinioClient minioClient(StorageProperties props, OkHttpClient minioHttpClient) {
        StorageProperties.Minio m = props.getMinio();
        return MinioClient.builder()
                .endpoint(m.getEndpoint())
                .credentials(m.getAccessKey(), m.getSecretKey())
                .region(m.getRegion())
                .httpClient(minioHttpClient)
                .build();
    }

    @Bean
    public StorageStrategy minioStorageStrategy(MinioClient client, StorageProperties props) {
        return new MinioStorageStrategy(client, props);
    }
}
