package com.cloudchunk.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "cloudchunk")
public class CloudchunkProperties {

    private Chunk chunk = new Chunk();
    private Upload upload = new Upload();
    private Executor executor = new Executor();
    private RateLimit rateLimit = new RateLimit();
    private Cache cache = new Cache();
    private Auth auth = new Auth();
    private Smtp smtp = new Smtp();
    private WebSocket webSocket = new WebSocket();

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

    /** 后台执行器容量：对外部系统（MinIO / MQ / DB）的并发上限 */
    public static class Executor {
        /** 对 MinIO / MQ 的通用 I/O 并发上限 */
        private int ioConcurrency = 64;
        /** 合并后清理分片、孤儿对象回收等粗粒度任务 */
        private int cleanupConcurrency = 32;
        /** 无法立即拿到许可时的等待时间（超时则拒绝） */
        private long acquireTimeoutMs = 500;

        public int getIoConcurrency() { return ioConcurrency; }
        public void setIoConcurrency(int ioConcurrency) { this.ioConcurrency = ioConcurrency; }
        public int getCleanupConcurrency() { return cleanupConcurrency; }
        public void setCleanupConcurrency(int cleanupConcurrency) { this.cleanupConcurrency = cleanupConcurrency; }
        public long getAcquireTimeoutMs() { return acquireTimeoutMs; }
        public void setAcquireTimeoutMs(long acquireTimeoutMs) { this.acquireTimeoutMs = acquireTimeoutMs; }
    }

    /** Redis 令牌桶限流配置（per-user） */
    public static class RateLimit {
        private boolean enabled = true;
        /** 分片上传：每用户每秒最多允许的请求速率（令牌补充速率 tokens/s） */
        private int uploadChunkRps = 30;
        /** 令牌桶容量（允许的瞬时突发） */
        private int uploadChunkBurst = 60;
        /** 下载：每用户每秒请求速率 */
        private int downloadRps = 50;
        private int downloadBurst = 100;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getUploadChunkRps() { return uploadChunkRps; }
        public void setUploadChunkRps(int uploadChunkRps) { this.uploadChunkRps = uploadChunkRps; }
        public int getUploadChunkBurst() { return uploadChunkBurst; }
        public void setUploadChunkBurst(int uploadChunkBurst) { this.uploadChunkBurst = uploadChunkBurst; }
        public int getDownloadRps() { return downloadRps; }
        public void setDownloadRps(int downloadRps) { this.downloadRps = downloadRps; }
        public int getDownloadBurst() { return downloadBurst; }
        public void setDownloadBurst(int downloadBurst) { this.downloadBurst = downloadBurst; }
    }

    /** FileMeta 本地 Caffeine 缓存 */
    public static class Cache {
        private boolean fileMetaEnabled = true;
        private int fileMetaMaxSize = 20000;
        private Duration fileMetaTtl = Duration.ofMinutes(5);

        public boolean isFileMetaEnabled() { return fileMetaEnabled; }
        public void setFileMetaEnabled(boolean fileMetaEnabled) { this.fileMetaEnabled = fileMetaEnabled; }
        public int getFileMetaMaxSize() { return fileMetaMaxSize; }
        public void setFileMetaMaxSize(int fileMetaMaxSize) { this.fileMetaMaxSize = fileMetaMaxSize; }
        public Duration getFileMetaTtl() { return fileMetaTtl; }
        public void setFileMetaTtl(Duration fileMetaTtl) { this.fileMetaTtl = fileMetaTtl; }
    }

    /** 登录与会话配置 */
    public static class Auth {
        private Duration tokenTtl = Duration.ofDays(7);

        public Duration getTokenTtl() { return tokenTtl; }
        public void setTokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; }
    }

    /** 出站邮件（验证码）。enabled=false 时开发模式仅记录日志，不实际发送。 */
    public static class Smtp {
        private boolean enabled = false;
        private String host = "";
        private int port = 587;
        private String username = "";
        private String password = "";
        private String from = "no-reply@cloudchunk.local";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
    }

    public static class WebSocket {
        private List<String> allowedOrigins = List.of(
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                "http://localhost:8080",
                "http://127.0.0.1:8080");

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }

    public Chunk getChunk() { return chunk; }
    public void setChunk(Chunk chunk) { this.chunk = chunk; }
    public Upload getUpload() { return upload; }
    public void setUpload(Upload upload) { this.upload = upload; }
    public Executor getExecutor() { return executor; }
    public void setExecutor(Executor executor) { this.executor = executor; }
    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }
    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }
    public Smtp getSmtp() { return smtp; }
    public void setSmtp(Smtp smtp) { this.smtp = smtp; }
    public WebSocket getWebSocket() { return webSocket; }
    public void setWebSocket(WebSocket webSocket) { this.webSocket = webSocket; }
}
