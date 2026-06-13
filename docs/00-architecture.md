# CloudChunk 整体架构

## 系统架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                           Browser (React 18)                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────────┐  │
│  │ Web Worker│  │ IndexedDB│  │ fetch API│  │ WebSocket Client   │  │
│  │ (hash-wasm)│ │(hash缓存)│  │(分片上传) │  │(实时进度推送)      │  │
│  └─────┬─────┘  └─────┬────┘  └───┬──────┘  └─────────┬──────────┘  │
└────────┼──────────────┼───────────┼────────────────────┼────────────┘
         │              │           │                    │
    ┌────▼──────────────▼───────────▼────────────────────▼─────────┐
    │                   Tomcat NIO (Virtual Threads)                │
    │  ┌──────────────────────────────────────────────────────┐    │
    │  │                cloudchunk-api                         │    │
    │  │  UploadController │ FileController │ TranscodeController│  │
    │  │  TraceFilter      │ RateLimitFilter│ WebSocket Handler  │  │
    │  └──────────────────────────────────────────────────────┘    │
    │  ┌──────────────────────────────────────────────────────┐    │
    │  │                cloudchunk-core                        │    │
    │  │  UploadService  │ FileMetaService  │ DownloadService  │    │
    │  │  ProgressStore  │ ChecksumService  │ QuotaService     │    │
    │  │  MergeTransactionService │ StaleSessionCleanupTask    │    │
    │  └──────────────────────────────────────────────────────┘    │
    │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
    │  │cloudchunk-  │  │cloudchunk-  │  │cloudchunk-          │  │
    │  │  storage    │  │  infra      │  │  mq                 │  │
    │  │StorageStrat.│  │RedisLock    │  │TranscodeProducer    │  │
    │  │MinIO impl   │  │RateLimiter  │  │ChecksumProducer     │  │
    │  └──────┬──────┘  └──────┬──────┘  └──────┬──────────────┘  │
    └─────────┼───────────────┼────────────────┼──────────────────┘
              │               │                │
    ┌─────────▼──┐  ┌────────▼───┐  ┌─────────▼──────────┐
    │   MinIO    │  │   Redis    │  │    RocketMQ        │
    │ (对象存储) │  │ (缓存/锁/  │  │  (异步消息)        │
    │            │  │  进度/限流) │  │                    │
    └────────────┘  └────────────┘  └─────────┬──────────┘
                                              │
                                    ┌─────────▼──────────┐
                                    │cloudchunk-transcode │
                                    │ ImageConsumer       │
                                    │ VideoConsumer       │
                                    │ DocConsumer         │
                                    │ DlqConsumer         │
                                    └────────────────────┘
              ┌────────────┐
              │   MySQL    │
              │ (元数据/   │
              │  会话/配额) │
              └────────────┘
```

## 模块依赖关系

```
cloudchunk-boot (启动模块)
  ├── cloudchunk-api        (REST 接口层)
  │     ├── cloudchunk-core     (业务核心)
  │     │     ├── cloudchunk-storage  (存储抽象)
  │     │     ├── cloudchunk-infra    (Redis 基础设施)
  │     │     └── cloudchunk-mq       (消息队列)
  │     └── cloudchunk-common   (公共枚举/工具/异常)
  └── cloudchunk-transcode  (转码消费者)
        ├── cloudchunk-core
        └── cloudchunk-common
```

## 核心数据流

| 链路 | 路径 | 关键组件 |
|------|------|----------|
| 分片上传 | Browser → Controller → UploadService → MinIO | ProgressStore(Redis), ChunkRecord(MySQL) |
| 直传上传 | Browser → MinIO (presigned PUT) → Controller(confirm) | StorageStrategy.presignUpload() |
| 秒传 | Browser → Controller → FileMetaService(MD5查询) | Redis SETNX 幂等锁 |
| 合并 | Controller → UploadService.merge() → MinIO ComposeObject | MergeTransactionService, RedisLock |
| 校验 | RocketMQ → ChecksumConsumer → ChecksumService | MD5 流式校验 |
| 转码 | RocketMQ → Image/Video/DocConsumer → MinIO | FFmpeg, Thumbnailator, Tika |
| 下载 | Controller → DownloadService → MinIO presign/getRange | Caffeine + Redis 两级缓存 |
