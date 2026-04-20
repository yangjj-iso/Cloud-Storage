package com.cloudchunk.transcode;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TranscodeProperties.class)
public class TranscodeConfig {
}
