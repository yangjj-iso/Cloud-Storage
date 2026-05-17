# 04 · 分片上传协议

> 本文档是 CloudChunk **最核心**的协议设计，涵盖秒传、分片上传、断点续传、服务端合并、异步校验五个环节。

---

## 1. 协议总览

```mermaid
stateDiagram-v2
    [*] --> INIT: POST /upload/init
    INIT --> INSTANT: 秒传命中
    INIT --> UPLOADING: 新会话/续传
    UPLOADING --> UPLOADING: 分片上传中
    UPLOADING --> MERGING: 全部到齐
    MERGING --> VERIFYING: Compose 成功
    VERIFYING --> AVAILABLE: MD5 一致
    VERIFYING --> BROKEN: MD5 不一致
    INSTANT --> [*]
    AVAILABLE --> [*]
    BROKEN --> [*]

    UPLOADING --> EXPIRED: 24h 未完成
    EXPIRED --> [*]
```

| 状态 | 含义 | `file_meta.status` |
|------|------|--------------------|
| INIT | 会话已创建，等待分片 | — |
| UPLOADING | 分片上传进行中 | 0 |
| MERGING | 已触发合并 | 1 |
| VERIFYING | 合并完成，异步校验中 | 1 |
| AVAILABLE | 整文件 MD5 一致，可用 | 2 |
| BROKEN | 校验失败，标记损坏 | 3 |
| INSTANT | 秒传命中，未经物理上传 | 2 |

---

## 2. 秒传（Instant Upload）

### 2.1 流程

```mermaid
sequenceDiagram
    autonumber
    participant C as 客户端
    participant API as UploadService
    participant R as Redis
    participant DB as MySQL
    participant S as Storage

    C->>C: 计算整文件 MD5 (spark-md5 增量)
    C->>API: POST /upload/init { fileMd5, fileName, size }
    API->>R: SETNX cc:upload:lock:{md5} EX 600
    alt 获取锁成功
        API->>DB: SELECT * FROM file_meta WHERE file_md5=? AND status=2
        alt 命中
            API->>DB: UPDATE file_meta SET ref_count = ref_count + 1
            API->>R: DEL cc:upload:lock:{md5}
            API-->>C: { mode: "INSTANT", fileId, url }
        else 未命中
            API->>DB: SELECT * FROM upload_session WHERE file_md5=?
            alt 存在进行中会话
                API->>R: HGETALL cc:upload:progress:{fileId}
                API->>R: DEL cc:upload:lock:{md5}
                API-->>C: { mode: "RESUME", fileId, uploaded: [...] }
            else 全新上传
                API->>DB: INSERT upload_session
                API->>R: HSET cc:upload:progress:{fileId} total N md5 X
                API->>R: DEL cc:upload:lock:{md5}
                API-->>C: { mode: "UPLOAD", fileId, chunkSize, chunkTotal }
            end
        end
    else 获取锁失败 (并发秒传)
        API-->>C: 200004 UPLOAD_IN_PROGRESS
    end
```

### 2.2 幂等控制

- **场景**：用户同时从两个 Tab 上传同一文件 → 两次 `/upload/init` 并发
- **问题**：不加锁会产生两条 `upload_session`，最终合并时重复落 `file_meta`
- **方案**：Redis `SETNX cc:upload:lock:{md5} EX 600`
- **释放**：初始化结束立即 DEL；异常情况靠 TTL 10min 自动过期

### 2.3 MD5 计算优化

- **前端**：`spark-md5` 增量计算，分块读取 `FileReader.readAsArrayBuffer`，避免 OOM
- **大文件降级**：文件 > 1 GB 时，默认改为**分片 MD5 级联**（`MD5(md5_0 | md5_1 | ...)`），牺牲秒传命中率换取客户端性能
- **后端校验**：合并完成后异步计算整文件 MD5 兜底（见 §6）

---

## 3. 分片上传

### 3.1 前端切片算法

```text
chunkSize   = 10 MB (当前默认，可配置 1~200 MB；建议不低于 5 MB)
chunkTotal  = ceil(fileSize / chunkSize)
for i in [0, chunkTotal):
    chunk   = file.slice(i * chunkSize, (i+1) * chunkSize)
    chunkMd5 = MD5(chunk)
    POST /upload/chunk { fileId, chunkIndex: i, chunkMd5, chunk }
```

**并发控制**：前端使用 **Promise Pool** 限制并发（建议 4~6 路），避免浏览器连接瓶颈与后端压力。

### 3.2 单分片上传时序

```mermaid
sequenceDiagram
    autonumber
    participant C as 客户端
    participant API as UploadService
    participant R as Redis
    participant S as Storage (MinIO)
    participant DB as MySQL

    C->>API: POST /upload/chunk (fileId, chunkIndex, chunkMd5, binary)
    API->>R: HGET cc:upload:progress:{fileId} {chunkIndex}
    alt 已完成 (幂等命中)
        API-->>C: { status: 1, etag: 已有 ETag }
    else 未完成
        API->>API: 校验 MD5(请求体) == chunkMd5
        alt 校验失败
            API-->>C: 200003 CHUNK_MD5_MISMATCH
        else 校验通过
            API->>S: putObject(bucket, {objectKey}.part{chunkIndex}, stream)
            S-->>API: ETag
            par 并行更新
                API->>R: HSET cc:upload:progress:{fileId} {chunkIndex} 1
            and
                API->>DB: UPSERT chunk_record (file_id, chunk_index, etag, status=1)
            end
            API-->>C: { status: 1, etag }
        end
    end

    opt 如果所有分片完成
        API->>API: 异步触发合并 (内部事件)
    end
```

### 3.3 分片命名规范

为便于 Compose 与清理，分片对象采用统一 key：

```
{bucket}/upload/{yyyyMMdd}/{fileId}/part.{chunkIndex:06d}
例: cloudchunk/upload/20250101/a1b2c3.../part.000042
```

合并完成后**最终对象**迁移至：
```
{bucket}/{yyyy}/{MM}/{dd}/{fileId}/{fileName}
例: cloudchunk/2025/01/01/a1b2c3.../demo.mp4
```

### 3.4 分片 MD5 校验

- **必做**：服务端接收分片后立即计算 MD5，与 `chunkMd5` 比对
- 失败则直接 `200003 CHUNK_MD5_MISMATCH`，不写 Redis，不占 MinIO 对象
- 前端收到后重传当前分片（**单分片重传**，不影响其他分片）

---

## 4. 断点续传

### 4.1 触发条件

- 浏览器刷新 / 关闭后重新打开
- 网络中断后恢复
- 显式点击"继续上传"

### 4.2 流程

```mermaid
sequenceDiagram
    autonumber
    participant C as 客户端
    participant API as UploadService
    participant R as Redis
    participant DB as MySQL
    participant S as Storage

    C->>C: 本地持久化 fileId (IndexedDB / LocalStorage)
    C->>API: POST /upload/init { fileMd5 }
    API->>DB: SELECT * FROM upload_session WHERE file_md5=? AND status=0
    alt 会话存在且未过期
        API->>R: HGETALL cc:upload:progress:{fileId}
        alt Redis 命中
            API-->>C: { mode: "RESUME", uploaded: [0,1,2,5,6] }
        else Redis 丢失 (TTL 过期 / 迁移)
            API->>DB: SELECT chunk_index FROM chunk_record WHERE file_id=? AND status=1
            API->>S: listObjects(prefix={fileId})
            API->>API: 取两者交集作为已完成集合
            API->>R: HMSET cc:upload:progress:{fileId} 重建
            API-->>C: { mode: "RESUME", uploaded: [...] }
        end
    end
    C->>C: 计算 missing = total - uploaded
    loop 并发上传 missing 分片
        C->>API: POST /upload/chunk
    end
```

### 4.3 Redis 兜底重建算法

```java
// 伪代码
Set<Integer> rebuildProgress(String fileId) {
    // 1. MySQL 侧
    List<ChunkRecord> dbChunks = chunkRecordMapper
        .select(fileId, ChunkStatus.DONE);
    Set<Integer> dbIndexes = dbChunks.stream()
        .map(ChunkRecord::getChunkIndex).collect(toSet());

    // 2. MinIO 侧（防止 MySQL 未落盘的边界情况）
    Set<Integer> storageIndexes = storage
        .listPartObjects(fileId)
        .stream().map(this::parseIndex).collect(toSet());

    // 3. 取交集（两边都有才算真的完成）
    Set<Integer> confirmed = Sets.intersection(dbIndexes, storageIndexes);

    // 4. 回填 Redis
    redis.hmset("cc:upload:progress:" + fileId,
        confirmed.stream().collect(toMap(i -> i.toString(), i -> "1")));
    redis.expire("cc:upload:progress:" + fileId, 24, HOURS);

    return confirmed;
}
```

### 4.4 过期处理

- `upload_session.expire_at` 默认 **24 小时**
- 定时任务每小时扫描 `status=0 AND expire_at < NOW()` 的会话：
  1. 置 `status=4`（过期）
  2. 清理 MinIO 中 `upload/*/fileId/*` 前缀对象
  3. 删除 Redis 进度 Key（若还存在）
  4. 删除 `chunk_record`

---

## 5. 服务端合并

### 5.1 触发条件

- **隐式触发**：最后一个分片上传成功后，判断已完成数 == 总数则自动触发
- **显式触发**：前端调 `POST /upload/merge/{fileId}`（幂等）

### 5.2 合并时序

```mermaid
sequenceDiagram
    autonumber
    participant API as UploadService
    participant R as Redis
    participant S as MinIO
    participant DB as MySQL
    participant MQ as RocketMQ

    API->>R: SETNX cc:upload:merge-lock:{fileId} EX 300
    alt 获取锁失败
        API-->>API: 200004 UPLOAD_IN_PROGRESS (已在合并)
    else 获取锁成功
        API->>R: HGETALL cc:upload:progress:{fileId}
        API->>API: 校验 count(status=1) == total

        API->>S: composeObject(<br/>target=final-key,<br/>sources=[part.000000, ..., part.N])
        Note right of S: MinIO 服务端拼接，<br/>数据不经应用层
        S-->>API: 合并对象 ETag

        API->>DB: BEGIN TRANSACTION
        API->>DB: INSERT INTO file_meta (..., status=1)
        API->>DB: UPDATE upload_session SET status=2
        API->>DB: COMMIT

        par 异步后续
            API->>MQ: send(topic=cloudchunk-checksum, fileId)
        and
            API->>S: 异步清理 part.* 对象
        end

        API->>R: DEL cc:upload:merge-lock:{fileId}
        API->>R: DEL cc:upload:progress:{fileId}
        API-->>API: 返回 MERGING 状态
    end
```

### 5.3 MinIO Compose Object 细节

- MinIO SDK：`ComposeObjectArgs` 支持最多 **10,000** 个源对象
- 每个源对象 **最小 5 MB**（最后一个除外），因此分片配置建议不低于 5 MB；当前开发配置默认 10 MB
- 超过 10,000 分片 → **分批 Compose**：先拼合成中间对象，再二次拼合
- Java SDK 示例：

```java
List<ComposeSource> sources = IntStream.range(0, total)
    .mapToObj(i -> ComposeSource.builder()
        .bucket(bucket)
        .object(partKey(fileId, i))
        .build())
    .toList();

minioClient.composeObject(
    ComposeObjectArgs.builder()
        .bucket(bucket)
        .object(finalKey)
        .sources(sources)
        .build());
```

### 5.4 合并失败处理

| 失败类型 | 处理 |
|----------|------|
| 分片数量不够 | 返回当前 uploaded，等前端重传缺失分片 |
| 某分片在 MinIO 被删 | 从 `uploaded` 移除该索引，前端重传 |
| MinIO 服务异常 | 置 `session.status=合并失败标记`，前端可重试 |
| 超时（> 5min） | 合并锁自动释放，前端可再次调 `/upload/merge` |

---

## 6. 异步整文件校验

> 为什么异步：合并一个 50 GB 文件后计算 MD5 需要数分钟，不能阻塞用户返回。

### 6.1 流程

```mermaid
sequenceDiagram
    autonumber
    participant MQ as RocketMQ (checksum topic)
    participant W as ChecksumWorker
    participant S as MinIO
    participant DB as MySQL
    participant MQ2 as RocketMQ (transcode topic)
    participant Notify as WebSocket/通知服务

    MQ->>W: consume { fileId, expectMd5 }
    W->>DB: SELECT * FROM file_meta WHERE file_id=?
    W->>S: getObject(bucket, finalKey) 流式读取
    W->>W: 流式计算 MD5 (DigestInputStream)
    alt MD5 一致
        W->>DB: UPDATE file_meta SET status=2 (AVAILABLE)
        W->>MQ2: send(topic=cloudchunk-transcode, fileId)
    else MD5 不一致
        W->>DB: UPDATE file_meta SET status=3 (BROKEN)
        W->>S: removeObject(finalKey)
        W->>Notify: push FILE_BROKEN 事件
    end
```

### 6.2 流式 MD5 计算

```java
try (InputStream in = storage.download(bucket, finalKey);
     DigestInputStream dis = new DigestInputStream(in, MessageDigest.getInstance("MD5"))) {
    byte[] buf = new byte[1024 * 1024]; // 1 MB buffer
    while (dis.read(buf) != -1) { /* just read */ }
    byte[] digest = dis.getMessageDigest().digest();
    return Hex.encodeHexString(digest);
}
```

关键点：
- **不加载整文件到内存**
- 使用 **1 MB buffer** 平衡吞吐与 GC 压力
- 校验时避免占用大量虚拟线程：专用线程池 `checksum-pool`（默认 4 路）

---

## 7. 完整端到端时序（快速参考）

```mermaid
sequenceDiagram
    autonumber
    participant C as 客户端
    participant API as UploadService
    participant R as Redis
    participant DB as MySQL
    participant S as MinIO
    participant MQ as RocketMQ
    participant W as Worker

    C->>C: ① 计算整文件 MD5
    C->>API: ② POST /upload/init
    API->>R: SETNX lock
    API->>DB: 查 file_meta
    alt 秒传命中
        API-->>C: ③a INSTANT + URL
    else 新会话
        API->>DB: INSERT upload_session
        API-->>C: ③b UPLOAD + fileId
        loop 分片并发
            C->>API: ④ POST /upload/chunk
            API->>S: putObject(part.N)
            API->>R: HSET progress N 1
            API-->>C: etag
        end
        API->>S: ⑤ composeObject(parts → final)
        API->>DB: INSERT file_meta(status=1)
        API->>MQ: send checksum
        API-->>C: ⑥ MERGING
        MQ->>W: ⑦ 异步校验
        W->>DB: status=2 AVAILABLE
        MQ->>W: ⑧ 异步转码
        W->>DB: update transcode_status
    end
```

---

## 8. 异常矩阵

| 异常 | 恢复策略 |
|------|----------|
| 网络断开 | 前端指数退避重试单分片，最多 3 次 |
| 分片 MD5 不一致 | 当前分片重传 |
| 整文件 MD5 不一致 | 文件标记 BROKEN + 通知重传 + 清理对象 |
| Redis 宕机恢复后进度丢失 | MySQL + MinIO 交集兜底重建 |
| 合并过程中应用重启 | 合并锁 TTL 过期后，前端可重新触发 |
| 会话 24h 未完成 | 定时任务清理，前端收到 `200001` 提示重新上传 |
| MinIO 单分片丢失 | 该分片标记未完成，前端重传；只合并现有完整分片在分片完整前不会触发 |

---

## 9. 性能考虑

| 优化点 | 说明 |
|--------|------|
| **分片并发度 4~6** | 经验值，避免浏览器连接上限 6/domain 被吃满 |
| **分片大小默认 10 MB** | 满足 MinIO Compose 非末尾源对象最小 5 MB 约束；过小会放大元数据开销 |
| **虚拟线程** | 上传接口 I/O 密集，Java 21 Virtual Thread 显著降内存 |
| **Redis pipeline** | 合并前 `HGETALL` + 后续 `DEL` 合并一次 RTT |
| **MySQL batch insert** | `chunk_record` 批量写（合并前兜底落表） |
| **Compose 异步清理 parts** | 合并成功后触发清理，不阻塞响应 |
