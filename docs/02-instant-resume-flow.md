# 链路二：秒传与断点续传详解

> 秒传和断点续传是 CloudChunk 的两大核心用户体验优化。本篇详细解析它们的实现原理和三级可靠性保障。

## 一、秒传（Instant Upload）

### 什么是秒传？

秒传的本质是**基于内容的去重**：如果服务器上已经存在一个内容完全相同的文件，就不需要再上传一次了。"完全相同"的判断依据是整文件 MD5。

举个例子：用户 A 上传了一个 500MB 的视频，用户 B 后来也要上传同一个视频。如果没有秒传，B 需要等待 500MB 的上传完成；有了秒传，B 在 init 阶段就能得知"这个文件已经有了"，整个过程耗时只有一次 HTTP 请求的时间（~50ms），对用户来说就是"瞬间完成"。

### 秒传流程

```
前端                              后端
 │                                 │
 │── POST /upload/init ──────→     │
 │   { fileMd5: "d41d8cd..." }     │
 │                                 │
 │                                 │── ① Redis SETNX 加锁
 │                                 │   key: cc:upload:lock:{fileMd5}
 │                                 │   目的：防止并发 init 同一 MD5
 │                                 │
 │                                 │── ② 查 file_meta 表
 │                                 │   WHERE file_md5 = 'd41d8cd...'
 │                                 │   AND status = 'AVAILABLE'
 │                                 │   命中！→ fileId = "abc123"
 │                                 │
 │                                 │── ③ 引用计数 +1
 │                                 │   UPDATE file_meta
 │                                 │   SET ref_count = ref_count + 1
 │                                 │   WHERE file_id = 'abc123'
 │                                 │
 │                                 │── ④ 释放锁
 │                                 │
 │  ←── { mode: "INSTANT",    ─────│
 │        fileId: "abc123",        │
 │        url: "https://minio..." }│
 │                                 │
 │   前端直接显示"上传成功"         │
 │   不上传任何字节                 │
```

### 核心代码解析

```java
var instant = fileMetaService.findAvailableByMd5(req.getFileMd5());
if (instant.isPresent()) {
    FileMeta m = instant.get();
    // 不创建新文件，只增加引用计数。
    // 引用计数的作用：当所有引用这个文件的用户都"删除"了它，ref_count 降到 0 时才真正删除存储对象。
    fileMetaService.incRefCount(m.getFileId());
    String url = storageFactory.current()
            .presignDownload(m.getBucket(), m.getObjectKey(), sessionTtl);
    return InitUploadResponse.instant(m.getFileId(), url);
}
```

### 为什么需要 Redis 锁？

设想没有锁的场景：
1. 用户 A 发起 init（fileMd5="abc"）→ 查 file_meta → 未命中 → 准备创建新会话
2. 用户 B 也发起 init（fileMd5="abc"）→ 查 file_meta → 未命中 → 也准备创建新会话
3. A 和 B 都创建了各自的 UploadSession → 同一个文件被上传了两次 → 浪费存储

加锁后：
1. A 获取锁 → 查询 → 未命中 → 创建会话 → 释放锁
2. B 尝试获取锁 → 等待（或失败） → A 上传完成 → B 重新 init → 秒传命中

> **面试要点**：秒传的核心是 `MD5 去重 + 引用计数`。面试官可能追问"MD5 会碰撞吗"，回答：理论上会，但概率极低（2^128 ≈ 3.4×10^38），实际工程中 MD5 去重是业界标准做法（百度网盘、阿里云盘都这么做）。如果面试官对安全性敏感，可以补充"后续可以升级为 SHA-256 或 MD5+文件大小联合去重"。

---

## 二、断点续传（Resume Upload）

### 什么是断点续传？

用户上传一个大文件，传到一半时网络断了（或者关掉了浏览器）。重新打开页面后拖入同一个文件，系统应该从断点继续上传，而不是从头来过。

断点续传的关键在于**进度记录**：后端需要知道哪些分片已经上传成功了。CloudChunk 用三级存储保障进度不丢失。

### 三级可靠性设计

```
                 查询速度      可靠性     恢复场景
┌──────────────┐
│ Level 1:     │  毫秒级       中        正常断点续传
│ Redis Hash   │  (O(1))      (内存，    （网络中断后重新上传）
│              │               重启丢失)
├──────────────┤
│ Level 2:     │  百毫秒级     高        Redis 宕机后恢复
│ MySQL        │  (索引查询)   (持久化)
│ chunk_record │
├──────────────┤
│ Level 3:     │  秒级         最高       MySQL 数据不一致时
│ MinIO        │  (list API)   (对象存储   兜底确认
│ list objects │               本身可靠)
└──────────────┘
```

### 进度加载代码

当用户重新 init 时，后端发现有未过期的 RUNNING 会话，就进入续传分支：

```java
private List<Integer> loadOrRebuildUploaded(UploadSession s) {
    // Level 1：尝试从 Redis 读取
    List<Integer> uploaded = progress.uploaded(s.getFileId());
    if (!uploaded.isEmpty()) return uploaded;   // 命中，毫秒级返回

    // Level 2：Redis 丢失（重启/内存淘汰）→ 从 MySQL 读取
    Set<Integer> db = new TreeSet<>();
    chunkMapper.selectList(new LambdaQueryWrapper<ChunkRecord>()
            .eq(ChunkRecord::getFileId, s.getFileId())
            .eq(ChunkRecord::getStatus, ChunkStatus.DONE))
            .forEach(r -> db.add(r.getChunkIndex()));
    if (db.isEmpty()) return List.of();

    // Level 3：与 MinIO 实际存在的对象取交集
    // 为什么取交集？因为 DB 记录了"后端认为成功了"，但 MinIO 对象可能因为磁盘故障丢失了。
    // 只有 DB 和 MinIO 都确认存在的分片才算真正完成。
    Set<Integer> confirmed = new TreeSet<>(db);
    try {
        var list = storageFactory.current().list(bucket, partPrefix, 20000);
        Set<Integer> store = new TreeSet<>();
        list.forEach(it -> {
            int idx = ObjectKeyUtils.parsePartIndex(it.objectKey());
            if (idx >= 0) store.add(idx);
        });
        confirmed.retainAll(store);  // 交集 = 最终可信的已完成分片
    } catch (Exception e) {
        log.warn("list storage for rebuild failed", e);
    }

    // 重建 Redis 进度
    progress.rebuild(s.getFileId(), s.getChunkTotal(), confirmed, sessionTtl);
    return new ArrayList<>(confirmed);
}
```

### 前端断点恢复

前端也有自己的断点记忆机制——基于 IndexedDB：

```typescript
// 每上传 10 片，持久化一次进度到 IndexedDB
if (idx % 10 === 0 || cursor >= indices.length) {
  const doneSet = [...uploaded];
  for (let j = 0; j < cursor; j++) doneSet.push(indices[j]);
  saveUploadState({
    fileId, fileMd5, fileName, fileSize,
    chunkSize, chunkTotal,
    uploadedChunks: doneSet,
    savedAt: Date.now(),
  }).catch(() => {});
}
```

前端的 IndexedDB 记录主要用于**关闭标签页后恢复**。下次打开页面时，前端先查 IndexedDB 是否有未完成的上传任务，有的话自动恢复（而不需要用户重新拖入文件）。但最终的可信进度仍以后端 Redis/MySQL 为准。

---

## 三、分片级去重（Chunk Dedup）

这是一个更细粒度的优化：即使整文件 MD5 不匹配（秒传未命中），部分分片的内容可能和其他文件的分片相同。

例如：用户上传 v1.zip（100 片），后来修改了几个文件，重新打包为 v2.zip（100 片）。v2 的大部分分片和 v1 是完全相同的，只有被修改的部分不同。

```java
public List<Integer> dedupChunks(String fileId, Map<Integer, String> chunkMd5Map) {
    List<Integer> deduped = new ArrayList<>();
    for (Map.Entry<Integer, String> entry : chunkMd5Map.entrySet()) {
        int idx = entry.getKey();
        String md5 = entry.getValue();

        // 查找其他会话中相同 MD5 的已完成分片
        ChunkRecord existing = chunkMapper.selectOne(
            ...eq(ChunkRecord::getChunkMd5, md5)
            ...eq(ChunkRecord::getStatus, ChunkStatus.DONE)
            ...ne(ChunkRecord::getFileId, fileId)...);

        if (existing != null) {
            // ★ 服务端拷贝：MinIO 内部复制，不下载到 Java 进程
            storage.copy(bucket, srcPartKey, bucket, dstPartKey);
            progress.markDone(fileId, idx, sessionTtl);
            deduped.add(idx);
        }
    }
    return deduped;
}
```

**关键**：`storage.copy()` 是 MinIO 服务端操作，数据在对象存储内部复制，不经过 Java 进程。相当于"秒传"在分片级别的应用。

> **面试要点**：三级可靠性是高频面试题。面试官问"Redis 挂了断点续传还能工作吗"，回答：**可以，从 MySQL + MinIO 重建进度**。追问"为什么还要和 MinIO 取交集"，回答：**防止 DB 记录成功但 MinIO 对象丢失的极端情况**。
