# 链路四：下载与缓存体系详解

> 下载看似简单，但在高并发和大文件场景下，缓存策略、Range 支持、带宽优化都有很多讲究。

## 一、两种下载方式

CloudChunk 提供两种下载路径，适用于不同场景：

### 方式一：预签名 URL 重定向（推荐）

后端不中转文件数据，只生成一个带签名的 MinIO URL 返回给前端。前端直接从 MinIO 下载。

**优点**：后端零带宽压力，MinIO 承担全部文件传输。
**适用场景**：普通文件下载、图片加载。

### 方式二：Range 分段下载（流式代理）

后端从 MinIO 读取指定范围的数据，流式转发给前端。支持 HTTP Range 请求。

**优点**：兼容不支持重定向的客户端，支持视频拖动播放。
**适用场景**：视频播放（拖动进度条时浏览器发 Range 请求）、需要鉴权拦截的场景。

### 预签名 URL 生成

```java
public PresignedUrl presign(String fileId, Duration ttl) {
    // 先验证文件是否可用（不是 BROKEN 或 DELETED）
    FileMeta meta = fileMetaService.getAvailableOrThrow(fileId);

    // ★ 先查 Redis 缓存：避免每次请求都调 MinIO 生成签名 URL
    String cached = fileMetaService.getCachedUrl(fileId);
    if (cached != null && !cached.isBlank()) {
        Duration remaining = fileMetaService.getCachedUrlTtl(fileId);
        return new PresignedUrl(cached, remaining);
    }

    // 缓存未中 → 调 MinIO 生成新的预签名 URL
    String url = storage.presignDownload(meta.getBucket(), meta.getObjectKey(), ttl);

    // ★ 缓存策略：缓存 TTL 比签名有效期短 1 分钟
    // 为什么？假设签名有效期 30 分钟，缓存也 30 分钟。
    // 那么用户在第 29 分钟拿到 URL，1 分钟后签名过期，下载失败。
    // 缩短 1 分钟后，最晚第 29 分钟 Redis 缓存过期，下一次请求会生成新 URL。
    Duration cacheTtl = ttl.compareTo(Duration.ofMinutes(5)) > 0
            ? ttl.minusMinutes(1) : ttl;
    fileMetaService.cacheUrl(fileId, url, cacheTtl);
    return new PresignedUrl(url, cacheTtl);
}
```

### Range 分段下载

```java
public DownloadStream open(String fileId, String rangeHeader) {
    FileMeta meta = fileMetaService.getAvailableOrThrow(fileId);
    long total = meta.getFileSize();

    // 解析 Range 头：bytes=0-999 或 bytes=1000-
    RangeSpec spec = RangeSpec.parse(rangeHeader, total);
    if (!spec.valid()) {
        throw BizException.of(ErrorCode.RANGE_NOT_SATISFIABLE, rangeHeader);
    }

    if (spec.isFull()) {
        // 无 Range 头 → 200 OK + 完整文件流
        InputStream in = storage.get(new GetRequest(meta.getBucket(), meta.getObjectKey()));
        return new DownloadStream(meta, in, 0, total - 1, total, true);
    }

    // 有 Range 头 → 206 Partial Content + 指定范围的流
    RangeStream rs = storage.getRange(GetRangeRequest.of(
            meta.getBucket(), meta.getObjectKey(), spec.start(), spec.end(), total));
    return new DownloadStream(meta, rs.stream(), rs.start(), rs.end(), rs.total(), false);
}
```

Controller 层根据 `DownloadStream.full` 决定返回 200 还是 206，并设置 `Content-Range` 响应头。这让浏览器的视频播放器能正确处理进度拖动。

---

## 二、两级缓存体系

CloudChunk 的文件元数据使用 **Caffeine（JVM 本地缓存）+ Redis（分布式缓存）** 两级架构：

```
读取链路：
Caffeine → miss → Redis → miss → MySQL → 回填两级缓存

写入/更新链路：
更新 MySQL → 失效 Caffeine → 失效 Redis → Pub/Sub 通知其他实例失效 Caffeine
```

### 为什么需要两级？

| 只用 Redis | 只用 Caffeine | 两级缓存 |
|-----------|-------------|---------|
| 每次查询需要网络往返（~1ms） | 多实例之间缓存不共享 | Caffeine 命中：0 网络开销 |
| 高并发时 Redis 成为瓶颈 | 更新不一致 | Redis 兜底：实例间共享 |
| | | MySQL 兜底：持久化 |

Caffeine 的优势是**零网络开销**——数据就在 JVM 堆里，读取只需几十纳秒。但问题是多实例部署时，实例 A 更新了文件元数据，实例 B 的 Caffeine 缓存还是旧数据。

### 跨实例缓存一致性 —— Redis Pub/Sub

解决方案是当任意实例写入/更新 file_meta 时，通过 Redis Pub/Sub 通知所有实例清除对应的 Caffeine 缓存：

```java
// FileMetaService.invalidateCache() — 写穿失效
private void invalidateCache(String fileId) {
    // 1. 清除本实例的 Caffeine
    if (localCache != null) localCache.invalidate(fileId);
    try {
        // 2. 清除 Redis 缓存
        redis.delete(RedisKeys.fileMeta(fileId));
        redis.delete(RedisKeys.fileUrl(fileId));
        // 3. ★ Pub/Sub 广播：通知所有其他实例
        redisTemplate.convertAndSend(RedisKeys.CHANNEL_CACHE_INVALIDATE, fileId);
    } catch (Exception e) {
        log.debug("invalidate cache failed: {}", fileId, e);
    }
}
```

其他实例通过 `CacheInvalidateListener` 接收消息并清除自己的 Caffeine：

```java
// CacheInvalidateListener — Redis Pub/Sub 消息监听
public class CacheInvalidateListener implements MessageListener {
    private final Cache<String, ?> localCache;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String fileId = new String(message.getBody(), StandardCharsets.UTF_8);
        localCache.invalidate(fileId);  // 清除本实例的 Caffeine
    }
}
```

### 为什么不用 Redis Pub/Sub 同步数据而是只同步"失效"？

1. **数据量**：file_meta 对象可能有 KB 级别的 JSON extra 字段，每次更新都广播整个对象浪费带宽
2. **一致性模型**：只广播"这个 key 失效了"，下次读取时各实例从 DB 拉最新数据。这是 **Cache-Aside** 模式的标准实践
3. **简单可靠**：失效操作是幂等的（多次失效同一个 key 没有副作用），不需要处理消息重复

### Caffeine 配置

```yaml
cloudchunk:
  cache:
    file-meta-enabled: true
    file-meta-max-size: 20000    # 最多缓存 20000 个 FileMeta 对象
    file-meta-ttl: PT5M          # 5 分钟后自动过期（即使没有收到失效通知）
```

TTL 是最后一道防线：即使 Pub/Sub 消息丢失（网络分区等极端情况），5 分钟后缓存也会自然过期。在这 5 分钟内可能返回旧数据，但不会永久不一致。

> **面试要点**：两级缓存 + Pub/Sub 是分布式系统面试高频题。核心回答：**Caffeine 解决热点读延迟，Redis Pub/Sub 解决多实例一致性，TTL 兜底极端情况**。追问"CAP 里你选了什么"，回答：**选择了 AP（可用性 + 分区容忍），允许短暂的缓存不一致（最终一致性）**。
