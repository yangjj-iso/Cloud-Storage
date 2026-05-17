# 11 · 配置参考

> 本文档汇总 CloudChunk 当前代码中的核心配置项、环境变量、Redis Key 和 RocketMQ Topic，便于本地调试与部署迁移。

## 1. 配置文件

| 文件 | 作用 |
|------|------|
| `cloudchunk-boot/src/main/resources/application.yml` | 默认配置，包含服务端口、数据源、Redis、RocketMQ、存储、限流、转码等 |
| `cloudchunk-boot/src/main/resources/application-dev.yml` | 开发环境覆写项 |
| `deploy/.env.example` | docker-compose 依赖服务的示例环境变量 |
| `deploy/docker-compose.yml` | 本地依赖服务编排 |
| `deploy/rocketmq/broker.conf` | RocketMQ Broker 配置 |
| `deploy/redis/redis.conf` | Redis 配置 |

## 2. 环境变量

### 2.1 Spring / 应用

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `dev` | Spring Profile |
| `STORAGE_TYPE` | `minio` | 存储实现：`minio` 或 `local` |
| `STORAGE_BUCKET` | `cloudchunk` | 默认对象存储桶 |
| `LOCAL_ROOT` | `./local-storage` | 本地磁盘存储根目录 |
| `LOCAL_BASE_URL` | `http://localhost:8080/api/v1/file` | 本地存储下载 URL 前缀 |
| `FFMPEG_PATH` | `ffmpeg` | FFmpeg 可执行文件路径 |

### 2.2 MySQL

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MYSQL_HOST` | `127.0.0.1` | MySQL 主机 |
| `MYSQL_PORT` | `3308` | MySQL 宿主机端口 |
| `MYSQL_DB` | `cloudchunk` | 数据库名 |
| `MYSQL_USER` | `root` | 用户名 |
| `MYSQL_PASSWORD` | `root` | 密码 |
| `MYSQL_ROOT_PASSWORD` | `root` | docker-compose MySQL root 密码 |

### 2.3 Redis

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `REDIS_HOST` | `127.0.0.1` | Redis 主机 |
| `REDIS_PORT` | `6380` | Redis 宿主机端口 |

### 2.4 RocketMQ

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `RMQ_NAMESRV` | `127.0.0.1:9876` | NameServer 地址 |

### 2.5 MinIO

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MINIO_ENDPOINT` | `http://127.0.0.1:9002` | MinIO API 地址 |
| `MINIO_AK` | `minioadmin` | 应用访问 MinIO 的 Access Key |
| `MINIO_SK` | `minioadmin` | 应用访问 MinIO 的 Secret Key |
| `MINIO_ROOT_USER` | `minioadmin` | docker-compose MinIO 控制台用户 |
| `MINIO_ROOT_PASSWORD` | `minioadmin` | docker-compose MinIO 控制台密码 |

## 3. 应用配置项

### 3.1 上传分片

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `cloudchunk.chunk.default-size` | `10485760` | 默认分片大小，当前为 10 MB |
| `cloudchunk.chunk.min-size` | `1048576` | 最小分片大小，1 MB |
| `cloudchunk.chunk.max-size` | `209715200` | 最大分片大小，200 MB |
| `cloudchunk.chunk.session-ttl` | `PT24H` | 上传会话 TTL |
| `cloudchunk.upload.md5-verify-thread-pool` | `16` | MD5 校验线程池规模 |
| `cloudchunk.upload.auto-merge` | `true` | 分片全部完成后是否自动合并 |

### 3.2 存储

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `cloudchunk.storage.type` | `${STORAGE_TYPE:minio}` | 存储策略 |
| `cloudchunk.storage.default-bucket` | `${STORAGE_BUCKET:cloudchunk}` | 默认桶 |
| `cloudchunk.storage.presign-ttl` | `PT30M` | 预签名 URL 有效期 |
| `cloudchunk.storage.compose-batch-size` | `1000` | Compose 分批大小 |
| `cloudchunk.storage.minio.endpoint` | `${MINIO_ENDPOINT:http://127.0.0.1:9002}` | MinIO API |
| `cloudchunk.storage.minio.region` | `cn-east-1` | MinIO Region |
| `cloudchunk.storage.minio.secure` | `false` | 是否使用 HTTPS |

### 3.3 并发与限流

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `spring.threads.virtual.enabled` | `true` | 启用 Java 21 虚拟线程 |
| `cloudchunk.executor.io-concurrency` | `64` | I/O 任务并发许可数 |
| `cloudchunk.executor.cleanup-concurrency` | `32` | 清理任务并发许可数 |
| `cloudchunk.executor.acquire-timeout-ms` | `500` | 获取并发许可超时 |
| `cloudchunk.rate-limit.enabled` | `true` | 是否启用限流 |
| `cloudchunk.rate-limit.upload-chunk-rps` | `30` | 单用户分片上传令牌补充速率 |
| `cloudchunk.rate-limit.upload-chunk-burst` | `60` | 单用户分片上传突发容量 |
| `cloudchunk.rate-limit.download-rps` | `50` | 单用户下载令牌补充速率 |
| `cloudchunk.rate-limit.download-burst` | `100` | 单用户下载突发容量 |

### 3.4 缓存

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `cloudchunk.cache.file-meta-enabled` | `true` | 是否启用 FileMeta 本地缓存 |
| `cloudchunk.cache.file-meta-max-size` | `20000` | FileMeta 本地缓存最大条目 |
| `cloudchunk.cache.file-meta-ttl` | `PT5M` | FileMeta 本地缓存 TTL |

### 3.5 转码

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `cloudchunk.transcode.ffmpeg-path` | `${FFMPEG_PATH:ffmpeg}` | FFmpeg 路径 |
| `cloudchunk.transcode.video-task-timeout` | `PT30M` | 视频任务超时 |
| `cloudchunk.transcode.video-max-duration-seconds` | `7200` | 视频最大时长 |
| `cloudchunk.transcode.image.sizes` | `200, 600, 1200` | 缩略图尺寸 |
| `cloudchunk.transcode.image.quality` | `0.85` | 图片输出质量 |

## 4. RocketMQ 约定

| 类型 | 名称 |
|------|------|
| 转码 Topic | `cloudchunk-transcode` |
| 校验 Topic | `cloudchunk-checksum` |
| 损坏通知 Topic | `cloudchunk-broken` |
| 图片转码 Tag | `img` |
| 视频转码 Tag | `video` |
| 文档转码 Tag | `doc` |
| 转码生产者组 | `PG-transcode` |
| 校验生产者组 | `PG-checksum` |
| 损坏通知生产者组 | `PG-broken` |
| 图片转码消费者组 | `CG-transcode-img` |
| 视频转码消费者组 | `CG-transcode-video` |
| 文档转码消费者组 | `CG-transcode-doc` |
| 校验消费者组 | `CG-checksum` |
| 损坏通知消费者组 | `CG-broken-notify` |

## 5. Redis Key 约定

| Key 模板 | 说明 |
|----------|------|
| `cc:upload:progress:{fileId}` | 分片上传进度 Hash |
| `cc:upload:session:{fileId}` | 上传会话热缓存 |
| `cc:upload:lock:{fileMd5}` | 秒传并发幂等锁 |
| `cc:upload:merge-lock:{fileId}` | 合并分布式锁 |
| `cc:file:url:{fileId}` | 下载预签名 URL 缓存 |
| `cc:file:meta:{fileId}` | 文件元数据热缓存 |
| `cc:file:md5:{md5}` | MD5 到 fileId 的反查 |
| `cc:transcode:sent:{fileId}` | 转码消息发送幂等 |
| `cc:transcode:done:{fileId}:{type}` | 转码完成幂等 |
| `cc:rate:upload:{userId}` | 上传令牌桶 |
| `cc:rate:download:{userId}` | 下载令牌桶 |

## 6. Profile 建议

| 环境 | 建议 |
|------|------|
| `dev` | 使用 docker-compose 依赖，端口按本文默认值 |
| `test` | 使用独立数据库或 Testcontainers，关闭高成本转码任务 |
| `prod` | 所有密码通过 Secret 注入，MinIO 使用 HTTPS，RocketMQ 关闭自动创建 Topic |

生产环境不要使用默认账号密码；`MINIO_SK`、`MYSQL_PASSWORD` 等敏感值应通过配置中心、Kubernetes Secret 或 CI/CD 密钥注入。
