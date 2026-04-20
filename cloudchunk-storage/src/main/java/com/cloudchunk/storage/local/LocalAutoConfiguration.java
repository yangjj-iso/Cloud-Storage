package com.cloudchunk.storage.local;

import com.cloudchunk.storage.StorageProperties;
import com.cloudchunk.storage.StorageStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@ConditionalOnProperty(prefix = "cloudchunk.storage", name = "type", havingValue = "local")
public class LocalAutoConfiguration {

    @Bean
    public StorageStrategy localStorageStrategy(StorageProperties props) {
        return new LocalStorageStrategy(props);
    }
}
