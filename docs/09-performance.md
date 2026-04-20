# 09 - 性能优化与并发设计

本文档汇总 CloudChunk 在上传 / 下载两条热路径上的全部性能优化措施，方便面试时按层次讲解。

---

## 一、上传链路优化

### 1.1 去除 @Transactional，避免长事务

**问题**：`uploadChunk` 原本标注 `@Transactional`，MinIO PUT 操作（数百毫秒~秒级）持有 DB 连接。在高并发场景下，HikariCP 连接池很快耗尽。

**解法**：移除 `@Transactional`，将 DB 写和 MinIO I/O 分离。仅 `MergeTransactionService.doMergeInTx()` 保持短事务（更新文件状态 + 扣减配额）。

**面试关键词**：事务粒度、连接池压力、I/O vs CPU 事务边界。

### 1.2 原子 Redis Lua — 分片进度追踪

**问题**：`markDone` + `doneCount` 需要 2 次 Redis RTT；`HGETALL` O(N) 统计完成数。

**解法**：单条 Lua 脚本完成三件事：
1. `HSETNX` 防重
2. `HINCRBY __count__` 原子计数
3. `EXPIRE` 续期

`doneCount` 降为 O(1)，RTT 从 2→1。

```lua
HSETNX key chunkIndex "1"
if added then HINCRBY key "__count__" 1 end
EXPIRE key ttl
return count
```

**面试关键词**：Redis 原子性、Lua 脚本、幂等、O(1) vs O(N)。

### 1.3 Raw Octet-Stream 上传端点

**问题**：`multipart/form-data` 让 Tomcat 先将 chunk 写入临时文件，产生一次不必要的磁盘 I/O。

**解法**：新增 `POST /chunk` `Content-Type: application/octet-stream` 端点，直接从 `HttpServletRequest.getInputStream()` 流式写入 MinIO，实现零中间文件。

**面试关键词**：零拷贝思想、Tomcat multipart overhead、streaming upload。

### 1.4 Streaming MD5 校验

使用 `DigestInputStream` 包装 chunk 数据流，在 MinIO PUT 的同时计算 MD5，不额外 buffer 整块数据。

---

## 二、并发控制

### 2.1 BoundedVirtualThreadExecutor

**问题**：`Thread.startVirtualThread()` 无背压，10 万并发直接涌向 MinIO/Redis，引发连接拒绝。

**解法**：自研 `BoundedVirtualThreadExecutor`：
- 底层用 `Executors.newVirtualThreadPerTaskExecutor()`。
- 入口加 `Semaphore(permits)` 做背压。
- `tryAcquire(timeout)` 失败抛 `RejectedExecutionException`。

注册两个 Bean：
- **ioExecutor** (64 permits)：MinIO PUT / MQ 补偿。
- **cleanupExecutor** (32 permits)：合并后分片清理。

**面试关键词**：虚拟线程、背压、Semaphore、资源隔离。

### 2.2 MinIO OkHttp Client 调优

默认 OkHttp `ConnectionPool(5)` + `maxRequestsPerHost(5)`，高并发下成为吞吐瓶颈。

自定义 OkHttp Bean：
- `ConnectionPool(256, 5min)`
- `Dispatcher.maxRequests = 256, maxRequestsPerHost = 256`
- `connectTimeout = 10s, readTimeout = 60s, writeTimeout = 60s`

### 2.3 Tomcat 线程与连接调优

```yaml
server.tomcat:
  threads.max: 400        # 虚拟线程兜底
  max-connections: 10000
  accept-count: 500
  connection-timeout: 20s
```

开启 `spring.threads.virtual.enabled=true`，实际请求由虚拟线程承载，平台线程仅做 NIO 事件分发。

---

## 三、下载链路优化

### 3.1 ETag + Cache-Control

- 响应头：`ETag: "文件MD5"`，`Cache-Control: public, max-age=86400, immutable`。
- 请求头匹配 `If-None-Match` → 304 Not Modified，零数据传输。

### 3.2 StreamingResponseBody

`StreamingResponseBody` 在 Tomcat 异步 I/O 线程中写出字节流，不阻塞 Servlet 线程。配合 256KB buffer 降低系统调用频率。

### 3.3 Range 请求（断点续传）

解析 `Range: bytes=start-end`，MinIO `getRange()`，返回 `206 Partial Content` + `Content-Range` 头。

---

## 四、缓存体系

### 4.1 FileMeta Caffeine L1 缓存

- **位置**：`FileMetaService` 内嵌 `Caffeine<String, FileMeta>`。
- **策略**：cache-aside，`findById` 先查本地再查 DB；写/删操作 `invalidateCache` 同时清除 Caffeine + Redis。
- **参数**：`maxSize=20000, expireAfterWrite=5min, recordStats=true`。
- **可观测**：`CaffeineCacheMetrics` 绑定 Micrometer → `/actuator/metrics/cache.gets`。

**面试关键词**：多级缓存、cache-aside、一致性、TTL vs 主动失效。

### 4.2 预签名 URL Redis 缓存

下载预签名 URL 写入 Redis `cc:file:url:{fileId}`，TTL 略短于签名有效期，避免重复签名。

---

## 五、限流

### 5.1 Redis 令牌桶（Token Bucket）

**Lua 脚本原子操作**：
1. `HMGET` 读取桶中剩余令牌 `tk` 和上次补充时间 `ts`。
2. 按 `elapsed × rate / 1e6` 计算应补充令牌数（微秒精度）。
3. 有令牌则扣 1 放行，否则拒绝。
4. `HMSET` 写回 + `EXPIRE` 自动过期。

**配置**（per-user）：
| 端点 | rate (tokens/s) | burst |
|------|-----------------|-------|
| 上传分片 | 30 | 60 |
| 下载 | 50 | 100 |

**RateLimitFilter** 在 Servlet Filter 层拦截，超限返回 `429 Too Many Requests`。

**面试关键词**：令牌桶 vs 漏桶 vs 滑动窗口、Lua 原子性、微秒精度、per-user 隔离。

---

## 六、可观测性

### Micrometer 指标

| 指标名 | 描述 |
|--------|------|
| `cache.gets{cache=fileMeta, result=hit/miss}` | Caffeine 命中/未命中 |
| `cache.size{cache=fileMeta}` | 当前缓存条目数 |
| `cache.evictions{cache=fileMeta}` | 驱逐次数 |
| `cloudchunk.rate_limit.rejected{endpoint=upload_chunk/download}` | 限流拒绝计数 |

通过 `/actuator/metrics` 或 Prometheus 采集端点查看。

---

## 七、面试高频追问参考

1. **为什么不用分布式缓存（如 Redis）做 FileMeta 的一级缓存？**
   → 本地缓存延迟 ~100ns，Redis ~1ms。高频读场景下 L1 Caffeine 减少 99% 的 Redis/DB 访问。

2. **Caffeine 缓存一致性怎么保证？**
   → 写穿透：所有写操作在 `invalidateCache` 中同时清除 Caffeine + Redis。TTL 兜底最终一致。多实例部署可通过 Redis Pub/Sub 广播失效事件。

3. **令牌桶和漏桶区别？**
   → 令牌桶允许突发（burst），漏桶平滑限流。业务场景中分片上传天然有突发需求，选令牌桶更合适。

4. **虚拟线程既然轻量，为什么还要 Semaphore 限制？**
   → 虚拟线程虽然创建开销极小，但下游系统（MinIO 连接数、Redis 连接数）有物理上限。Semaphore 保护的是下游资源而非线程本身。

5. **Lua 脚本在 Redis Cluster 模式下能跑吗？**
   → 可以，但所有 KEY 必须落在同一个 slot。本项目中每个 Lua 脚本只操作一个 KEY，天然兼容 Cluster。

6. **如何验证这些优化有效？**
   → Micrometer 暴露 cache hit rate、rate limit rejection count；可用 wrk / k6 做压测，对比优化前后 P99 延迟和吞吐。
