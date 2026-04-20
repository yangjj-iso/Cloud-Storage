package com.cloudchunk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.cloudchunk")
public class CloudchunkApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudchunkApplication.class, args);
    }
}
