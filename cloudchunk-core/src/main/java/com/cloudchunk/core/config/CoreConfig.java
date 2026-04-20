package com.cloudchunk.core.config;

import com.cloudchunk.core.CloudchunkProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CloudchunkProperties.class)
public class CoreConfig {
}
