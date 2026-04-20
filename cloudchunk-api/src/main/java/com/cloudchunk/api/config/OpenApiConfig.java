package com.cloudchunk.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cloudchunkOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("CloudChunk API")
                .version("0.1.0")
                .description("Distributed file storage service: chunked upload / resume / instant / async transcoding.")
                .license(new License().name("MIT")));
    }
}
