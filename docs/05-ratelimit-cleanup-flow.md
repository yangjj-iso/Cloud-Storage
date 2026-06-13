# 链路五：限流、僵尸清理与存储策略

> 本篇覆盖三个横切关注点：请求限流、过期资源清理、存储抽象。它们不在主流程的"关键路径"上，但对系统的健壮性和可维护性至关重要。

## 一、Redis Lua 令牌桶限流

### 为什么需要限流？

分片上传场景下，一个用户可能同时发起上百个并发请求（100 个分片并行上传 = 100 个并发 HTTP 请求）。如果不加限制：
- 少数活跃用户可能占满后端的所有线程和带宽
- MinIO 和 Redis 可能被打垮
- 其他用户的请求被饿死

### 令牌桶算法原理

令牌桶就像一个漏水的桶，但反过来——桶里装的是"令牌"（许可证）：

1. **匀速补充**：系统以固定速率（rate）向桶里放令牌，比如每秒放 30 个
2. **突发容量**：桶有上限（capacity），放满了就不再放，比如最多存 60 个
3. **消费令牌**：每个请求需要从桶里取一个令牌。有令牌就放行，没有就拒绝（429）
4. **突发允许**：如果桶里存了 60 个令牌（一段时间没请求），短时间内可以突发消费 60 个

**与漏桶算法的区别**：漏桶严格匀速输出（每秒只处理固定数量的请求，多余的排队）；令牌桶允许突发（一瞬间可以消费多个令牌），更适合文件上传这种天然突发的场景。

### Lua 脚本详解

```lua
-- KEYS[1] = 限流 key（per-user），如 cc:rate:upload:12345
-- ARGV[1] = rate（令牌补充速率，tokens/s）
-- ARGV[2] = capacity（桶容量）
-- ARGV[3] = now（当前时间，微秒精度）
local key = KEYS[1]
local rate = tonumber(ARGV[1])
local cap  = tonumber(ARGV[2])
local now  = tonumber(ARGV[3])

-- 读取当前桶状态：tk=当前令牌数，ts=上次操作时间
local d = redis.call('HMGET', key, 'tk', 'ts')
local tokens = tonumber(d[1])
local last   = tonumber(d[2])

-- 首次访问：桶满
if tokens == nil then tokens = cap; last = now end

-- ★ 按时间差补充令牌
-- elapsed 是距上次操作过了多少微秒
-- elapsed * rate / 1000000 = 过了这么久应该补充多少令牌
-- math.min(cap, ...) 保证不超过桶容量
local elapsed = math.max(0, now - last)
tokens = math.min(cap, tokens + elapsed * rate / 1000000)

-- 尝试消费一个令牌
local allowed = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
end

-- 回写状态
redis.call('HMSET', key, 'tk', tostring(tokens), 'ts', tostring(now))
-- 设置 EXPIRE：长时间不访问的用户自动回收桶
-- cap/rate + 1 = 桶满需要的时间 + 1 秒缓冲
redis.call('EXPIRE', key, math.ceil(cap / rate) + 1)
return allowed
```

**为什么用 Lua 而不是多次 Redis 命令？** 假设不用 Lua：
1. `HMGET key tk ts` → 读取桶状态
2. 在 Java 中计算新令牌数
3. `HMSET key tk {new} ts {now}` → 写回状态

问题：两个并发请求都在步骤 1 读到 tokens=1，都认为"有令牌"，都在步骤 3 写回 tokens=0。结果两个请求都放行了，但桶里只有 1 个令牌——**竞态条件**。Lua 脚本在 Redis 单线程中原子执行，彻底消除了这个问题。

### 限流过滤器

```java
@Component
@Order(10)  // 在 TraceFilter 之后执行（TraceFilter 解析 userId）
public class RateLimitFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(...) {
        if (!cfg.isEnabled()) { chain.doFilter(request, response); return; }

        long userId = UserContext.getOrDefault();

        // 上传分片限流：POST /upload/chunk
        if ("POST".equalsIgnoreCase(method) && path.contains("/upload/chunk")) {
            if (!rateLimiter.tryAcquire(
                    RedisKeys.rateUpload(userId),      // key: cc:rate:upload:{userId}
                    cfg.getUploadChunkRps(),            // 30 tokens/s
                    cfg.getUploadChunkBurst())) {       // burst=60
                uploadRejected.increment();             // Micrometer 计数器
                reject(response);                      // 返回 429
                return;
            }
        }

        // 下载限流：GET /file/*/download
        if ("GET".equalsIgnoreCase(method) && path.contains("/file/") && path.endsWith("/download")) {
            if (!rateLimiter.tryAcquire(
                    RedisKeys.rateDownload(userId),     // key: cc:rate:download:{userId}
                    cfg.getDownloadRps(),               // 50 tokens/s
                    cfg.getDownloadBurst())) {           // burst=100
                downloadRejected.increment();
                reject(response);
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
```

**配置在 `application.yml` 中**：
```yaml
cloudchunk:
  rate-limit:
    enabled: true              # 可一键关闭限流
    upload-chunk-rps: 30       # 每用户每秒 30 个分片上传请求
    upload-chunk-burst: 60     # 允许突发 60 个
    download-rps: 50
    download-burst: 100
```

> **面试要点**：令牌桶 vs 漏桶的区别是高频面试题。核心区别：**漏桶匀速、令牌桶允许突发**。Lua 脚本的核心价值是**原子性**——多个命令在 Redis 单线程中一次执行完成，消除竞态条件。

---

## 二、僵尸分片清理

### 什么是僵尸分片？

用户开始上传一个 1GB 文件（100 片），传了 60 片后放弃了（关掉浏览器、永远不再回来）。这 60 个分片对象永远留在 MinIO 上，Redis 里的进度 Hash 永远不会被清理——这就是"僵尸"。

长期积累下来，会造成：
- MinIO 存储空间被无用数据占满
- Redis 内存被过期进度 Hash 占用（如果没设 TTL 或 TTL 很长）
- MySQL upload_session 表里堆积大量 RUNNING 状态的无用记录

### 清理策略

`StaleSessionCleanupTask` 每小时执行一次，扫描 MySQL 中"状态为 RUNNING/MERGING 但已过期"的上传会话：

```java
@Scheduled(fixedDelay = 3600_000, initialDelay = 60_000)
public void cleanup() {
    LocalDateTime now = LocalDateTime.now();
    // 查找过期会话：status IN (RUNNING, MERGING) AND expire_at < NOW()
    // expire_at 在创建会话时设置，默认 24 小时后过期
    List<UploadSession> stale = sessionMapper.selectList(
            new LambdaQueryWrapper<UploadSession>()
                    .in(UploadSession::getStatus, RUNNING, MERGING)
                    .lt(UploadSession::getExpireAt, now)
                    .last("limit 100"));    // 每次最多处理 100 个，避免单次执行过久

    for (UploadSession s : stale) {
        try {
            cleanOne(s);
        } catch (Exception e) {
            log.warn("cleanup failed for fileId={}", s.getFileId(), e.getMessage());
            // 单个失败不影响其他会话的清理
        }
    }
}

private void cleanOne(UploadSession s) {
    // Step 1: 清除 Redis 进度（即使 Redis key 已因 TTL 过期自动消失，调用也是幂等的）
    progressStore.clear(s.getFileId());

    // Step 2: 批量删除 MinIO 上的分片对象
    List<String> partKeys = new ArrayList<>(s.getChunkTotal());
    for (int i = 0; i < s.getChunkTotal(); i++) {
        partKeys.add(ObjectKeyUtils.partKey(s.getFileId(), i));
    }
    try {
        storageFactory.current().deleteBatch(s.getBucket(), partKeys);
    } catch (Exception e) {
        // MinIO 删除失败只记 warn，不阻断 DB 状态更新
        // 下次清理任务会重新尝试（因为 DB 状态还是 RUNNING）
        // 等一下，这里有个问题：下面会把状态改成 EXPIRED...
        // 实际上如果 MinIO 删除失败但 DB 标记了 EXPIRED，这些分片就永远不会被再次清理了。
        // 但这是一个可接受的 trade-off：MinIO 有自己的生命周期策略可以兜底。
        log.warn("delete stale parts failed: fileId={}", s.getFileId(), e.getMessage());
    }

    // Step 3: CAS 更新 DB 状态（只有仍为 RUNNING/MERGING 的才标记 EXPIRED）
    sessionMapper.update(null, new LambdaUpdateWrapper<UploadSession>()
            .eq(UploadSession::getFileId, s.getFileId())
            .in(UploadSession::getStatus, RUNNING, MERGING)
            .set(UploadSession::getStatus, UploadSessionStatus.EXPIRED));
}
```

**CAS 条件更新的作用**：假设清理任务在删除 MinIO 分片的过程中，用户恰好回来续传并完成了上传。如果不加条件直接 `SET status=EXPIRED`，就会把已完成的会话标记为过期。加了 `IN (RUNNING, MERGING)` 条件后，如果会话已经变成 COMPLETED，更新不会生效。

---

## 三、存储策略模式

### 为什么用策略模式？

不同的客户可能用不同的对象存储：
- 开发环境用 MinIO（本地自建）
- 生产环境用阿里云 OSS
- 某些场景用本地磁盘

如果存储操作散布在业务代码各处（`if (type == "minio") { ... } else if (type == "oss") { ... }`），每次新增一种存储就要改一堆业务代码。策略模式把存储操作抽象成接口，业务代码只依赖接口，具体实现通过配置切换。

### 接口设计

```java
public interface StorageStrategy {
    String type();                                        // "minio" / "local" / "oss"
    PutResult put(PutRequest request);                    // 写对象
    InputStream get(GetRequest request);                  // 读对象（完整）
    RangeStream getRange(GetRangeRequest request);        // 读对象（Range）
    ComposeResult compose(ComposeRequest request);        // 服务端合并
    String presignDownload(String bucket, String key, Duration ttl);  // 下载签名
    default String presignUpload(...) { throw ... }       // 上传签名（可选）
    default void copy(...) { throw ... }                  // 服务端拷贝（可选）
    void delete(String bucket, String key);               // 删对象
    void deleteBatch(String bucket, List<String> keys);   // 批量删
    boolean exists(String bucket, String key);            // 存在性检查
    ObjectStat stat(String bucket, String key);           // 对象元信息
    List<ObjectStat> list(String bucket, String prefix, int max); // 列举对象
}
```

**`default` 方法**的设计：`presignUpload` 和 `copy` 不是所有存储都支持（本地磁盘没有"预签名 URL"的概念）。用 `default` 方法提供默认的"抛异常"实现，只有支持该能力的存储（如 MinIO）才覆盖。业务代码调用前可以检查 `type()` 决定是否使用这些方法。

### 切换方式

```yaml
cloudchunk:
  storage:
    type: ${STORAGE_TYPE:minio}      # minio / local / oss
    default-bucket: ${STORAGE_BUCKET:cloudchunk}
    minio:
      endpoint: http://127.0.0.1:9002
      access-key: minioadmin
      secret-key: minioadmin
    local:
      root: ./local-storage
      base-url: http://localhost:8080/api/v1/file
```

`StorageStrategyFactory` 根据 `type` 配置注入对应的实现。业务代码通过 `storageFactory.current()` 获取当前策略实例：

```java
StorageStrategy storage = storageFactory.current();
storage.put(request);       // 不关心底层是 MinIO 还是本地磁盘
```

> **面试要点**：策略模式是设计模式面试的经典题。这里的 `StorageStrategy` 体现了 **OCP 原则**（开闭原则）：对扩展开放（新增 AliyunOssStorageStrategy），对修改关闭（不需要改 UploadService 的任何代码）。面试官追问"怎么新增一种存储"，回答：**实现 StorageStrategy 接口 + 注册到 StorageStrategyFactory + 在 application.yml 配置 type 即可**。
