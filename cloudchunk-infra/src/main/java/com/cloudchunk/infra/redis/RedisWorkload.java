package com.cloudchunk.infra.redis;

/**
 * Redis 上承载的几类负载。它们的可再生性完全不同，因此适合分开部署。
 * <p>
 * 会话数据丢了就是全员掉线，无法恢复；其余三类都能从权威数据重建或直接失效重来。
 * 挤在同一个实例里意味着共享爆炸半径：缓存侧一次驱逐风暴就能把登录态一起带走。
 */
public enum RedisWorkload {

    /** 登录 Token、登录失败计数、邮箱验证码。不可再生，主实例承载。 */
    SESSION,
    /** 分片上传进度。丢失后可从 chunk_record 与对象存储重建。 */
    PROGRESS,
    /** 令牌桶。丢失后退化为放行，秒级自愈。 */
    RATE_LIMIT,
    /** 文件元数据与预签名 URL 缓存。丢失只是一次回源。 */
    CACHE
}
