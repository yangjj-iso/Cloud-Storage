package com.cloudchunk.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "cloudchunk.storage")
public class StorageProperties {

    private String type = "minio";
    private String defaultBucket = "cloudchunk";
    private Duration presignTtl = Duration.ofMinutes(30);
    private int composeBatchSize = 1000;

    private Minio minio = new Minio();
    private Local local = new Local();

    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String region = "cn-east-1";
        private boolean secure = false;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public boolean isSecure() { return secure; }
        public void setSecure(boolean secure) { this.secure = secure; }
    }

    public static class Local {
        private String root = "./local-storage";
        private String baseUrl = "http://localhost:8080/api/v1/file";

        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDefaultBucket() { return defaultBucket; }
    public void setDefaultBucket(String defaultBucket) { this.defaultBucket = defaultBucket; }
    public Duration getPresignTtl() { return presignTtl; }
    public void setPresignTtl(Duration presignTtl) { this.presignTtl = presignTtl; }
    public int getComposeBatchSize() { return composeBatchSize; }
    public void setComposeBatchSize(int composeBatchSize) { this.composeBatchSize = composeBatchSize; }
    public Minio getMinio() { return minio; }
    public void setMinio(Minio minio) { this.minio = minio; }
    public Local getLocal() { return local; }
    public void setLocal(Local local) { this.local = local; }
}
