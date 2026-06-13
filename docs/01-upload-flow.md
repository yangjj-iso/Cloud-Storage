# 链路一：分片上传全链路详解

> 本篇覆盖从用户拖入文件到合并完成的完整数据通路，是 CloudChunk 最核心的业务链路。

## 为什么需要分片上传？

传统的单文件上传在面对 GB 级大文件时有三个致命问题：

1. **浏览器内存溢出**：如果一次性把整个文件读进内存再发送，1GB 文件会直接让浏览器标签页崩溃。
2. **网络中断白费**：上传到 80% 时网络断了，整个文件得从头来过，用户体验极差。
3. **服务端压力集中**：单个超大 POST 请求会长时间占用 Tomcat 线程和数据库连接。

分片上传的核心思想是**把大象装进冰箱**：把文件切成小块（默认 10MB），独立上传每一块，全部到齐后在服务端拼回去。这样每个网络请求都很小，失败了只需重传那一片，而不是整个文件。

## 整体流程图

```
前端（浏览器）                        后端（Spring Boot）                     基础设施
 │                                     │                                      │
 │── 1. Web Worker 计算 MD5 ──→        │                                      │
 │   (hash-wasm WASM 加速)             │                                      │
 │   (2MB 缓冲流式读取)                │                                      │
 │   (结果缓存 IndexedDB)              │                                      │
 │                                     │                                      │
 │── 2. POST /upload/init ──────→      │                                      │
 │   {fileName, fileSize, fileMd5,     │── Redis SETNX 锁串行化 ──→          │→ Redis
 │    chunkSize, chunkTotal}           │── 查 file_meta (秒传) ──→            │← MySQL
 │                                     │── 查 upload_session (续传) ──→       │← MySQL
 │                                     │── 新建 session + Redis 进度 ──→      │→ MySQL + Redis
 │  ←── {fileId, mode, uploaded,  ─────│                                      │
 │       missing, expireAt}            │                                      │
 │                                     │                                      │
 │── 3. GET /upload/presign/{fileId} ──→                                      │
 │  ←── {0: "https://minio...",    ────│── presignUpload() ──→                │← MinIO
 │       1: "https://minio...", ...}   │                                      │
 │                                     │                                      │
 │── 4a. PUT 直传 MinIO ──────────────────────────────────────────────→       │→ MinIO
 │── 4b. POST /upload/confirm ────→    │── stat() 确认对象存在 ──→            │← MinIO
 │                                     │── upsert ChunkRecord ──→             │→ MySQL
 │                                     │── Lua 原子进度更新 ──→               │→ Redis
 │  ←── {allReady: true/false} ────────│                                      │
 │                                     │                                      │
 │── 5. POST /upload/merge/{fileId} ──→│                                      │
 │                                     │── Redis 分布式锁 ──→                │→ Redis
 │                                     │── ComposeObject 服务端合并 ──→       │→ MinIO
 │                                     │── 事务：file_meta + session ──→      │→ MySQL
 │                                     │── 发 ChecksumMessage ──→             │→ RocketMQ
 │                                     │── 异步清理分片对象 ──→               │→ MinIO
 │  ←── {fileId, status, objectKey} ───│                                      │
```

## Step 1：前端哈希计算 —— 为什么用 Web Worker + WASM？

在上传任何数据之前，前端需要计算两种 MD5：
- **整文件 MD5**：用于秒传判断（后端查 file_meta 表是否已有相同 MD5 的文件）
- **每片 MD5**：用于上传后校验（确保传输过程中数据没有损坏）

**传统做法的问题**：先读一遍文件算文件 MD5，再逐片读取算分片 MD5 —— 文件被读了两遍，大文件耗时翻倍。

**CloudChunk 的优化**：单次遍历（single-pass），同时维护两个 hasher，一个算文件级 MD5，一个算当前分片的 MD5。遇到分片边界时，分片 hasher 输出结果并重置，文件 hasher 继续累积。

```typescript
// md5.worker.ts — 单次遍历双哈希
const fileHasher = await createMD5();    // 文件级 hasher，贯穿整个文件
let chunkHasher = await createMD5();     // 分片级 hasher，每到分片边界重置

while (offset < total) {
  // 每次只读 2MB 到内存，不是整个文件
  const buf = await blob.slice(offset, Math.min(offset + 2*1024*1024, total)).arrayBuffer();
  const view = new Uint8Array(buf);

  fileHasher.update(view);               // 喂给文件级 hasher

  // 喂给分片级 hasher，检测分片边界
  let pos = 0;
  while (pos < view.length) {
    const remain = chunkSize - chunkBytes;
    const feed = Math.min(remain, view.length - pos);
    chunkHasher.update(view.subarray(pos, pos + feed));
    chunkBytes += feed;
    pos += feed;
    if (chunkBytes >= chunkSize) {        // 到达分片边界
      chunkHashes.push(chunkHasher.digest('hex'));   // 输出当前分片 MD5
      chunkHasher = await createMD5();               // 重置分片 hasher
      chunkBytes = 0;
    }
  }
  offset += buf.byteLength;
}
```

**为什么用 Web Worker**：MD5 计算是 CPU 密集型操作，如果在主线程执行，大文件计算期间页面会完全卡死（无法滚动、点击、渲染动画）。Web Worker 在独立线程运行，主线程保持流畅。

**为什么用 hash-wasm**：它把 MD5 算法编译成 WebAssembly，比纯 JavaScript 实现（如 SparkMD5）快 3-5 倍。对于 1GB 文件，hash-wasm 约 2 秒完成，SparkMD5 需要 8-10 秒。

**为什么缓存到 IndexedDB**：用户拖入同一个文件两次（比如第一次上传失败了重新拖入），第二次不需要重新计算 MD5。IndexedDB 是浏览器本地存储，刷新页面后仍然有效。

> **面试要点**：被问到"前端怎么计算大文件 MD5 不卡顿"时，回答三个关键词：**Web Worker 隔离线程** + **hash-wasm WASM 加速** + **2MB 流式缓冲不加载整文件**。追问"为什么不用 SparkMD5"时，说出 WASM 的吞吐量优势和 single-pass 双哈希的 I/O 优化。

---

## Step 2：初始化上传会话 —— init 的三路分支

`POST /upload/init` 是上传的入口，它不接收任何文件内容，只接收文件的元数据（文件名、大小、MD5、分片计划）。后端根据这些信息决定走哪条路径：

| 条件 | 返回 mode | 含义 |
|------|-----------|------|
| file_meta 表存在相同 MD5 的 AVAILABLE 文件 | `INSTANT` | 秒传命中，不需要上传 |
| upload_session 表存在未过期的 RUNNING 会话 | `RESUME` | 断点续传，只上传缺失分片 |
| 以上都不命中 | `UPLOAD` | 全新上传 |

```java
public InitUploadResponse init(InitUploadRequest req, long userId) {
    validateInit(req);   // 校验 chunkSize 范围、chunkTotal 是否匹配 fileSize
    quotaService.checkCapacityOrThrow(userId, req.getFileSize());  // 配额检查

    // ★ Redis 锁：同一个 fileMd5 的 init 请求串行化
    // 不加锁的问题：两个用户同时上传相同文件 → 都没命中秒传 → 都创建新会话 → 浪费存储
    String lockKey = RedisKeys.uploadInstantLock(req.getFileMd5());
    String token = IdUtils.uuid32();
    if (!redisLock.tryLock(lockKey, token, Duration.ofMinutes(10))) {
        throw BizException.of(ErrorCode.UPLOAD_IN_PROGRESS, req.getFileMd5());
    }
    try {
        // 分支 1：秒传
        var instant = fileMetaService.findAvailableByMd5(req.getFileMd5());
        if (instant.isPresent()) {
            fileMetaService.incRefCount(instant.get().getFileId());  // 引用计数+1
            return InitUploadResponse.instant(fileId, url);
        }

        // 分支 2：续传
        UploadSession existing = sessionMapper.selectOne(/*RUNNING + 未过期*/);
        if (existing != null) {
            List<Integer> uploaded = loadOrRebuildUploaded(existing);
            List<Integer> missing = buildMissing(total, uploaded);
            return InitUploadResponse.resume(fileId, uploaded, missing);
        }

        // 分支 3：全新上传
        UploadSession s = createSession(req, userId);
        progress.init(s.getFileId(), s.getChunkTotal(), sessionTtl);
        return InitUploadResponse.upload(fileId, chunkSize, chunkTotal, expireAt);
    } finally {
        redisLock.unlock(lockKey, token);  // 无论成功失败都释放锁
    }
}
```

**为什么 init 不接收文件内容**：init 阶段的目的是"决策"而非"传输"。它需要快速返回告诉前端该怎么做（秒传/续传/新建），如果 init 请求本身就携带文件内容，那秒传场景下这些数据就白传了。

**为什么用 Redis 锁而不是 MySQL 行锁**：init 请求可能很频繁（用户重复拖入相同文件），用 Redis SETNX 锁的获取和释放都是毫秒级，MySQL 行锁在高并发下会阻塞连接池。

> **面试要点**：init 的三路分支是面试高频考点。面试官会问"秒传是怎么实现的"、"断点续传的进度怎么保存"，答案都在 init 方法里。

---

## Step 3：分片上传 —— 为什么去掉 @Transactional？

`uploadChunk` 是整个系统中并发最高的方法——100 个分片并行上传意味着 100 个并发请求同时进入这个方法。这里有一个关键的架构决策：**不使用 @Transactional**。

**问题背景**：如果给 `uploadChunk` 加 `@Transactional`，方法执行期间会一直持有一个 HikariCP 数据库连接。而方法内部包含 MinIO PUT 操作，这是一个秒级的网络 I/O（上传 10MB 分片到 MinIO 需要数百毫秒到几秒）。

**后果计算**（Little's Law）：
- 并发 = QPS × 平均耗时
- 假设 100 个分片并发上传，每片 MinIO PUT 耗时 1 秒
- 需要 100 × 1 = 100 个数据库连接同时被占用
- 但 HikariCP 默认连接池大小只有 50，直接打爆连接池

**解决方案**：去掉 @Transactional，让方法内的两处 DB 写（ChunkRecord upsert、ProgressStore Lua）各自独立执行，不包裹在同一个事务中。这两个操作本身都是幂等的：
- `chunkMapper.upsert()` 用 `ON DUPLICATE KEY UPDATE`，重复执行结果一样
- `progress.markDone()` 用 Lua HSETNX，重复调用 count 不会增加

```java
// ★ 无 @Transactional —— 这是有意为之的设计决策
public ChunkUploadResponse uploadChunk(String fileId, int chunkIndex,
                                       String chunkMd5, long chunkSize,
                                       InputStream data) throws IOException {
    UploadSession s = requireRunningSession(fileId);

    // 幂等检查：分片已完成则直接返回
    if (progress.isDone(fileId, chunkIndex)) { return cachedResponse; }

    // ★ DigestInputStream：边传边算 MD5
    // 不把分片 readAllBytes() 进堆，而是包装一个 DigestInputStream
    // 当 MinIO SDK 从 stream 读取数据时，DigestInputStream 同步更新 MD5 摘要
    MessageDigest digest = MessageDigest.getInstance("MD5");
    DigestInputStream dis = new DigestInputStream(data, digest);

    // 写入 MinIO（秒级网络 I/O，这就是为什么不能包在事务里）
    PutResult pr = storage.put(PutRequest.of(bucket, partKey, dis, chunkSize, ...));

    // 传输完成后验证 MD5
    String actualMd5 = Md5Utils.hex(digest.digest());
    if (!actualMd5.equalsIgnoreCase(chunkMd5)) {
        // MD5 不匹配 → 异步删除错误分片
        ioExecutor.execute(() -> storage.delete(bucket, partKey));
        throw BizException.of(ErrorCode.CHUNK_MD5_MISMATCH, ...);
    }

    // DB 记录（幂等 upsert）
    chunkMapper.upsert(rec);

    // Redis Lua 原子进度更新
    int done = progress.markDone(fileId, chunkIndex, sessionTtl);
    boolean allReady = done == s.getChunkTotal();
    return new ChunkUploadResponse(fileId, chunkIndex, etag, 1, allReady);
}
```

**DigestInputStream 的妙处**：传统做法是先把分片读到 `byte[]` 数组（堆内存分配），算完 MD5 再上传到 MinIO——分片被读了两遍，内存占用翻倍。`DigestInputStream` 包装原始流，MinIO SDK 读流时自动更新 MD5 摘要，一遍搞定。

> **面试要点**：去 @Transactional 是非常典型的性能优化面试题。面试官可能会追问"去事务后一致性怎么保证"，回答：**两处写操作都是幂等的**——DB 用 upsert，Redis 用 HSETNX。失败后前端重试，最终状态一定正确。

---

## Step 4：Redis Lua 原子进度 —— 为什么不用普通 Redis 命令？

分片进度存储在 Redis Hash 中：`cc:upload:progress:{fileId}`，字段名是分片序号，值为 "1" 表示已完成。另有两个元字段 `__total__`（总片数）和 `__count__`（已完成数）。

**如果用普通命令**：
```
HSET key {chunkIndex} "1"      // 标记完成
HINCRBY key __count__ 1        // 计数+1
EXPIRE key {ttl}               // 续期
```
三次网络往返（3 RTT），而且有并发问题：两个分片同时完成 → 都执行 HSET + HINCRBY → count 被加了两次（因为 HSET 不判断字段是否已存在）。

**Lua 脚本解决方案**：

```lua
-- 一次 RTT 完成三个操作，Redis 单线程保证原子执行
local added = redis.call('HSETNX', KEYS[1], ARGV[1], '1')   -- ① 幂等置位
local count
if added == 1 then
  count = redis.call('HINCRBY', KEYS[1], '__count__', 1)     -- ② 新片才加计数
else
  count = tonumber(redis.call('HGET', KEYS[1], '__count__'))  -- ③ 已存在不加
end
redis.call('EXPIRE', KEYS[1], ARGV[2])                       -- ④ 续期
return count
```

**三个关键保证**：
1. **幂等**：`HSETNX` 只在字段不存在时设置，重复上传同一分片不会重复计数
2. **原子**：Redis 单线程执行 Lua，100 个并发分片不会互相干扰
3. **高效**：一次 RTT 完成置位 + 计数 + 续期，延迟只有普通方案的 1/3

> **面试要点**：这是"Redis Lua 脚本"和"分布式计数器"的经典应用场景。面试官会问"为什么不用 HSET + HINCRBY"，核心答案是**幂等性**和**原子性**。

---

## Step 5：Presigned PUT 直传 vs 后端代理 —— 两条数据路径

CloudChunk 支持两种分片上传路径，前端会优先选择直传，失败后自动降级：

| 对比 | Presigned PUT 直传 | 后端代理上传 |
|------|-------------------|-------------|
| 数据路径 | 浏览器 → MinIO | 浏览器 → 后端 → MinIO |
| 后端负载 | 只处理元数据确认 | 需要中转全部分片数据 |
| 带宽压力 | 后端零带宽 | 后端带宽 = 上传带宽 |
| 前提条件 | MinIO 配置了 CORS | 无额外要求 |
| MD5 校验 | 前端自行校验 | 后端 DigestInputStream 校验 |

**前端降级逻辑**：

```typescript
let usePresign = true;  // 默认尝试直传

for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
  try {
    if (presignUrl && usePresign) {
      // 直传 MinIO
      await fetch(presignUrl, { method: 'PUT', body: blob });
      await api.confirmChunk(fileId, idx, chunkMd5);  // 回调后端确认
    } else {
      // 降级：后端代理
      await api.uploadChunk({ fileId, chunkIndex, chunkMd5, chunkSize, blob });
    }
    return;
  } catch (e) {
    if (presignUrl && usePresign) {
      usePresign = false;         // 直传失败（可能是 CORS），全局降级
      attempt--;                  // ★ CORS 降级不消耗重试次数
      continue;
    }
    // 指数退避重试
    await sleep(500 * Math.pow(2, attempt));
  }
}
```

**为什么 CORS 降级不消耗重试次数**：CORS 问题是环境配置问题，不是网络瞬时故障。一旦发生，说明 MinIO 没有正确配置跨域，后续所有直传都会失败。降级到后端代理后，应该保留完整的重试次数给后端代理路径。

---

## Step 6：合并 —— MinIO ComposeObject 的威力

所有分片上传完成后，需要把它们拼成一个完整的文件对象。这里有一个**关键的性能优化**：不在 Java 应用层合并，而是调用 MinIO 的 `ComposeObject` API 在对象存储内部合并。

**传统做法**：下载所有分片到 Java 进程内存 → 按顺序写入一个新文件 → 上传回 MinIO。1GB 文件 = 下载 1GB + 上传 1GB = 2GB 网络传输 + 1GB 堆内存占用。

**CloudChunk 的做法**：调用 `ComposeObject`，MinIO 在自己的存储层直接把分片拼起来，数据不经过 Java 进程。1GB 合并耗时从 7.8 秒降到 11 毫秒（实测）。

```java
private MergeResult doMerge(UploadSession s) {
    // 1. 验证进度完整性
    int doneCount = progress.doneCount(s.getFileId());
    if (doneCount != s.getChunkTotal()) { /* 重建进度后再检查 */ }

    // 2. 标记状态为 MERGING
    mergeTx.markMerging(s.getFileId());

    // 3. ★ 服务端合并 —— 只发一个 HTTP 请求给 MinIO
    List<String> sources = new ArrayList<>(s.getChunkTotal());
    for (int i = 0; i < s.getChunkTotal(); i++) {
        sources.add(ObjectKeyUtils.partKey(s.getFileId(), i));
    }
    ComposeResult cr = storage.compose(new ComposeRequest(bucket, objectKey, sources));

    // 4. 事务内完成数据库操作（独立 Service 保证 @Transactional 代理生效）
    mergeTx.finalizeSuccess(s, storage.type());

    // 5. 投递校验消息到 RocketMQ（异步校验 + 转码）
    checksumProducer.publish(msg);

    // 6. 异步清理临时分片对象
    progress.clear(s.getFileId());
    cleanupPartsAsync(bucket, sources);

    return new MergeResult(fileId, "MERGED", objectKey, etag);
}
```

**MergeTransactionService 为什么独立成 Service**：Spring AOP 代理的限制——同一个类的方法互相调用（比如 `UploadService.doMerge()` 调用 `this.finalizeSuccess()`），不会走 AOP 代理，`@Transactional` 注解不生效。把事务操作抽到独立的 `MergeTransactionService`，调用时通过 Spring 注入的代理对象，事务才能正常开启。

> **面试要点**：合并阶段是性能优化的亮点。面试官问"1GB 文件合并怎么做到 11ms"时，回答：**MinIO ComposeObject API 服务端合并，数据不经过应用层**。追问"如果分片数量超过 MinIO 限制怎么办"，回答：**分批合并 + 中间对象策略**（见 `MinioStorageStrategy.compose()` 的分批逻辑）。
