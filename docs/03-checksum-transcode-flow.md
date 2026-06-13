# 链路三：异步校验与转码管道详解

> 文件合并只是上传的结束，不是文件"可用"的开始。合并后的文件还需要经过 MD5 校验和媒体转码，才能真正对外提供服务。本篇详解这条异步管道的设计。

## 一、为什么校验和转码要异步？

### 问题：如果合并时同步校验

假设用户上传了一个 1GB 的视频文件。合并完成后如果同步做 MD5 校验（需要读取整个 1GB 文件计算 MD5）和视频转码（FFmpeg 抽首帧），整个合并接口的响应时间可能超过 30 秒。

这会导致：
1. **前端超时**：浏览器默认 60 秒超时，大文件场景经常触发
2. **连接池耗尽**：合并接口长时间占用线程和数据库连接
3. **用户体验差**：用户看着进度条卡在 "合并中…" 不动

### 解决方案：RocketMQ 解耦

将校验和转码设计为异步消息：合并成功后立即返回给前端"已合并"，同时发送一条消息到 RocketMQ。后台消费者接收消息后异步执行校验和转码。这样上传接口的 TP99 不受校验/转码耗时影响。

```
时间线：
├─ 合并（11ms，MinIO ComposeObject）──→ 返回前端"已合并" 
│
├─ [异步] 校验（秒级，读取整文件算 MD5）──→ 标记为"可用"
│
└─ [异步] 转码（分钟级，FFmpeg 处理）──→ 生成缩略图/封面
```

---

## 二、校验流程

### 为什么要二次校验？

前端上传前已经计算了整文件 MD5，为什么合并后还要再算一次？

原因是**分片传输可能出错**。虽然每个分片上传时都有 MD5 校验，但合并后的文件 MD5 可能和原始文件不同，例如：
- 分片顺序被打乱（理论上不会，但极端 bug 场景下可能）
- MinIO ComposeObject 内部数据损坏（极罕见但不是零概率）
- 某个分片的 MD5 校验被跳过（比如 Presigned PUT 直传路径只信任前端传来的 MD5）

二次校验是最终的**数据完整性保障**。

### 校验核心代码

```java
// ChecksumService.verify() — 合并后的整文件 MD5 校验
public void verify(ChecksumMessage msg) {
    FileMeta meta = fileMetaService.findById(msg.getFileId()).orElse(null);
    if (meta == null) return;                                  // 元数据缺失，跳过
    if (meta.getStatus() == FileStatus.AVAILABLE) return;      // 已校验过（幂等）

    // ★ 从 MinIO 流式读取合并后的整文件，边读边算 MD5
    // 不是 readAllBytes() 到内存再算，而是流式计算，内存占用 O(1)
    String actual;
    try (InputStream in = storageFactory.current()
            .get(new GetRequest(msg.getBucket(), msg.getObjectKey()))) {
        actual = Md5Utils.md5(in);
    }

    if (!actual.equalsIgnoreCase(msg.getExpectMd5())) {
        // ★ MD5 不匹配 → 三步处理
        // 1. 标记文件状态为 BROKEN
        fileMetaService.updateStatus(msg.getFileId(), FileStatus.BROKEN);
        // 2. 发送损坏通知（可对接告警系统）
        brokenProducer.publish(BrokenMessage.of(msg.getFileId(), "md5 mismatch"));
        // 3. 删除 MinIO 上的错误对象（避免占用存储空间）
        storageFactory.current().delete(msg.getBucket(), msg.getObjectKey());
        return;
    }

    // MD5 匹配 → 文件状态从 MERGED 升级为 AVAILABLE（可用）
    fileMetaService.updateStatus(msg.getFileId(), FileStatus.AVAILABLE);

    // 投递转码消息
    TranscodeMessage t = new TranscodeMessage();
    t.setFileId(msg.getFileId());
    t.setMimeType(msg.getMimeType());
    // ...
    try {
        transcodeProducer.publish(t);
    } catch (Exception e) {
        // ★ 转码消息发送失败不会阻断校验流程
        // TranscodeCompensationTask 会定时扫描 AVAILABLE + NONE 的记录补发
        log.warn("transcode produce failed (non-fatal): {}", msg.getFileId(), e);
    }
}
```

**关键设计**：转码消息发送失败只记 warn 日志，不抛异常。因为校验是必须成功的核心流程，不能因为转码消息发送失败而回滚校验结果。遗漏的转码会被 `TranscodeCompensationTask` 补偿。

---

## 三、转码消费者 —— 模板方法模式

### 设计模式选择

转码有三种类型（图片、视频、文档），它们的处理流程高度相似：
1. 幂等检查（是否已处理过）
2. 更新状态为 RUNNING
3. 执行具体转码逻辑（不同类型不同实现）
4. 回写结果到 file_meta
5. 标记为 SUCCESS 或 FAILED

这就是经典的**模板方法模式**（Template Method Pattern）：在抽象父类中定义流程骨架，把可变的部分留给子类实现。

### 抽象消费者

```java
public abstract class AbstractTranscodeConsumer implements RocketMQListener<TranscodeMessage> {
    // 子类必须实现的两个方法
    protected abstract String taskType();          // "image" / "video" / "doc"
    protected abstract TranscodeResult doTranscode(TranscodeMessage msg) throws Exception;

    @Override
    public void onMessage(TranscodeMessage msg) {
        // ① 幂等检查：Redis key cc:transcode:done:{fileId}:{type}
        String doneKey = RedisKeys.transcodeDone(msg.getFileId(), taskType());
        if (Boolean.TRUE.equals(redis.hasKey(doneKey))) return;

        // ② 更新状态 + 记录 TranscodeRecord
        fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.RUNNING);
        TranscodeRecord record = newRecord(msg);
        recordMapper.insert(record);

        try {
            // ③ 子类执行具体转码逻辑
            TranscodeResult result = doTranscode(msg);

            // ④ 成功：回写结果
            fileMetaService.updateExtra(msg.getFileId(), extraJson, result.getThumbnailUrl());
            fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.SUCCESS);
            redis.set(doneKey, "1", Duration.ofDays(7));  // 标记完成防重
        } catch (TranscodeException te) {
            // ⑤ 可重试异常：检查重试次数
            boolean giveUp = !te.isRetryable() || currentRetry >= MAX_RETRY_BEFORE_GIVE_UP;
            if (!giveUp) {
                throw te;   // 重新抛出让 RocketMQ 重投
            }
            // 超过最大重试次数 → 放弃，标记 FAILED
            fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.FAILED);
        } catch (Exception e) {
            // ⑥ 不可重试异常 → 直接标记 FAILED
            fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.FAILED);
        }
    }
}
```

### 三种消费者实现

| 消费者 | RocketMQ Tag | 并发线程 | 处理逻辑 |
|--------|-------------|---------|---------|
| ImageTranscodeConsumer | `img` | 8 | Thumbnailator 生成 s/m/l 三种尺寸缩略图 |
| VideoTranscodeConsumer | `video` | 2 | FFmpeg 抽取首帧封面 |
| DocTranscodeConsumer | `doc` | 4 | Apache Tika 提取文本摘要 |

Tag 分发由 `TranscodeProducer` 根据 MIME 类型决定：`image/*` → `img`，`video/*` → `video`，`application/pdf` 等 → `doc`。

---

## 四、失败兜底 —— 三层防护

### 第一层：RocketMQ 自动重试

消费者抛出异常后，RocketMQ 会自动重投消息。默认重试 16 次，间隔从 10s 递增到 2h。但 `AbstractTranscodeConsumer` 主动设置了 `MAX_RETRY_BEFORE_GIVE_UP = 8`，超过 8 次就不再重试。

### 第二层：死信队列（DLQ）

超过 RocketMQ 最大重试次数的消息会进入死信队列 `%DLQ%CG-transcode-img`。`TranscodeDlqConsumer` 监听这个队列：

```java
@RocketMQMessageListener(topic = MqTopics.DLQ_TRANSCODE_IMG, ...)
public class TranscodeDlqConsumer implements RocketMQListener<TranscodeMessage> {
    @Override
    public void onMessage(TranscodeMessage msg) {
        // 输出告警日志（运维监控系统可以订阅这个日志关键词）
        log.error("[TRANSCODE-DLQ-ALERT] fileId={}, mimeType={}, retryCount={}",
                msg.getFileId(), msg.getMimeType(), msg.getRetryCount());
        // 标记文件转码状态为 FAILED
        fileMetaService.updateTranscodeStatus(msg.getFileId(), TranscodeStatus.FAILED);
        // 记录一条 DLQ 类型的 TranscodeRecord
        recordMapper.insert(dlqRecord);
    }
}
```

### 第三层：补偿扫描

即使 RocketMQ 消息丢失（极端情况），`TranscodeCompensationTask` 每 5 分钟扫描一次数据库，找出"已校验通过但还没转码"的文件，重新发送转码消息：

```java
@Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
public void compensate() {
    // 找出 status=AVAILABLE 且 transcode_status=NONE 且创建超过 10 分钟的记录
    List<FileMeta> orphans = fileMetaMapper.selectList(...);
    for (FileMeta m : orphans) {
        if (transcodeProducer.publish(msg)) {
            published++;
        } else {
            // MIME 类型不支持转码 → 标记 SKIP 避免反复扫描
            fileMetaService.updateTranscodeStatus(m.getFileId(), TranscodeStatus.SKIP);
        }
    }
}
```

**为什么给正常链路留 10 分钟余量**：如果一个文件刚刚合并通过校验，正常的转码流程可能还在进行中（或者消息正在 RocketMQ 队列中排队）。设置 10 分钟阈值避免误判"遗漏"。

> **面试要点**：异步转码管道的三层防护是架构设计的亮点。面试官问"消息丢了怎么办"，回答：**RocketMQ 自动重试 → DLQ 兜底告警 → 定时补偿扫描**。三层防护确保"最终一致性"。追问"模板方法模式的好处"，回答：**新增转码类型只需继承 AbstractTranscodeConsumer 并实现 doTranscode()，零侵入主流程**。
