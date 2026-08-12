package com.cloudchunk.infra.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 把可再生的 Redis 负载从主实例上剥离出去。
 * <p>
 * 主实例（{@code spring.data.redis}）始终承载会话数据；这里配置的每一项都是可选的，
 * 不配置就继续留在主实例上，行为与拆分前完全一致。
 */
@ConfigurationProperties(prefix = "cloudchunk.redis")
public class RedisWorkloadProperties {

    private Endpoint progress;
    private Endpoint rateLimit;
    private Endpoint cache;

    public Endpoint getProgress() { return progress; }
    public void setProgress(Endpoint progress) { this.progress = progress; }
    public Endpoint getRateLimit() { return rateLimit; }
    public void setRateLimit(Endpoint rateLimit) { this.rateLimit = rateLimit; }
    public Endpoint getCache() { return cache; }
    public void setCache(Endpoint cache) { this.cache = cache; }

    public Endpoint forWorkload(RedisWorkload workload) {
        return switch (workload) {
            case PROGRESS -> progress;
            case RATE_LIMIT -> rateLimit;
            case CACHE -> cache;
            case SESSION -> null;
        };
    }

    public static class Endpoint {
        private String host;
        private int port = 6379;
        private int database = 0;
        private String password;

        /** 只有配了 host 才算真正指定了独立实例。 */
        public boolean isConfigured() {
            return host != null && !host.isBlank();
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
