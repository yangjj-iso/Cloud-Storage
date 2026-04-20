package com.cloudchunk.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "cloudchunk")
public class CloudchunkProperties {

    private Chunk chunk = new Chunk();
    private Upload upload = new Upload();

    public static class Chunk {
        private int defaultSize = 5 * 1024 * 1024;
        private int minSize = 1024 * 1024;
        private int maxSize = 100 * 1024 * 1024;
        private Duration sessionTtl = Duration.ofHours(24);

        public int getDefaultSize() { return defaultSize; }
        public void setDefaultSize(int defaultSize) { this.defaultSize = defaultSize; }
        public int getMinSize() { return minSize; }
        public void setMinSize(int minSize) { this.minSize = minSize; }
        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        public Duration getSessionTtl() { return sessionTtl; }
        public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }
    }

    public static class Upload {
        private int md5VerifyThreadPool = 4;
        private boolean autoMerge = true;

        public int getMd5VerifyThreadPool() { return md5VerifyThreadPool; }
        public void setMd5VerifyThreadPool(int md5VerifyThreadPool) { this.md5VerifyThreadPool = md5VerifyThreadPool; }
        public boolean isAutoMerge() { return autoMerge; }
        public void setAutoMerge(boolean autoMerge) { this.autoMerge = autoMerge; }
    }

    public Chunk getChunk() { return chunk; }
    public void setChunk(Chunk chunk) { this.chunk = chunk; }
    public Upload getUpload() { return upload; }
    public void setUpload(Upload upload) { this.upload = upload; }
}
