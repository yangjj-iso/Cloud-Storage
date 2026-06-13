package com.cloudchunk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.cloudchunk")
@EnableScheduling
public class CloudchunkApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudchunkApplication.class, args);
    }
}
