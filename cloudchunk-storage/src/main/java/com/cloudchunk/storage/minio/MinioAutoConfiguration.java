package com.cloudchunk.storage.minio;

import com.cloudchunk.storage.StorageProperties;
import com.cloudchunk.storage.StorageStrategy;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@ConditionalOnProperty(prefix = "cloudchunk.storage", name = "type", havingValue = "minio", matchIfMissing = true)
public class MinioAutoConfiguration {

    @Bean(destroyMethod = "close")
    public MinioClient minioClient(StorageProperties props) {
        StorageProperties.Minio m = props.getMinio();
        return MinioClient.builder()
                .endpoint(m.getEndpoint())
                .credentials(m.getAccessKey(), m.getSecretKey())
                .region(m.getRegion())
                .build();
    }

    @Bean
    public StorageStrategy minioStorageStrategy(MinioClient client, StorageProperties props) {
        return new MinioStorageStrategy(client, props);
    }
}
