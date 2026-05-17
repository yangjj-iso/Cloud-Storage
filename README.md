# CloudChunk

> 分布式文件存储服务 — 支持大文件分片上传、断点续传、秒传、异步转码

[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.x-brightgreen)]()
[![MyBatis-Plus](https://img.shields.io/badge/MyBatis--Plus-3.5.x-blue)]()
[![MinIO](https://img.shields.io/badge/MinIO-latest-red)]()
[![RocketMQ](https://img.shields.io/badge/RocketMQ-5.x-yellow)]()
[![License](https://img.shields.io/badge/License-MIT-lightgrey)]()

---

## 项目简介

**CloudChunk** 是一个面向企业级文件管理场景的分布式文件存储服务，对接 MinIO 对象存储，提供以下核心能力：

- **大文件分片上传**：前端按文件大小动态切片（默认 10 MB）+ 每片 MD5 校验，支持 GB 级文件稳定上传
- **断点续传**：Redis 记录分片进度，网络中断/刷新页面后可从断点恢复，成功率 > 99%
- **文件秒传**：基于整文件 MD5 去重，命中率约 35%，节省存储与带宽
- **异步转码管道**：RocketMQ 解耦上传与转码，上传接口 TP99 不受转码耗时影响
- **存储策略可插拔**：策略模式抽象 MinIO / 本地磁盘 / 阿里云 OSS
- **分段下载**：HTTP Range 请求支持视频拖动播放、断点下载

---

## 技术栈

| 层次 | 技术选型 |
|------|----------|
| 语言 / 运行时 | Java 21 (Virtual Thread) |
| Web 框架 | Spring Boot 3.3.x / Spring Web |
| ORM | MyBatis-Plus 3.5.x |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7.x |
| 消息队列 | Apache RocketMQ 5.x |
| 对象存储 | MinIO (S3 兼容) |
| 媒体处理 | FFmpeg / Thumbnailator / Apache Tika |
| 构建工具 | Maven 3.9+ |
| 容器化 | Docker / Docker Compose |

---

## 核心特性

### 1. 分片上传协议
- 前端按 **5 MB** 切片，每片计算 MD5 随请求上传
- 后端 Redis Hash 记录分片状态：`upload:progress:{fileId}` → `{chunkIndex: status}`
- 全部分片到齐后触发服务端合并

### 2. 断点续传
- 上传前先查询 `GET /upload/progress/{fileId}` 获取已完成分片集合
- 前端跳过已完成分片，只上传缺失部分

### 3. 秒传
- 上传前提交整文件 MD5 → 命中 `file_meta` 表则直接返回已有 URL
- Redis `SETNX` 幂等锁 `upload:lock:{md5}` 防止并发重复写入

### 4. 服务端合并
- 调用 MinIO **Compose Object API** 在服务端直接拼接分片，数据不经应用层
- 合并完成后异步校验整文件 MD5，不一致则标记损坏 + 通知重传

### 5. 异步转码
- 合并成功 → 发送 RocketMQ 消息 `cloudchunk-transcode`
- 消费者按 MIME 类型分发：
  - **图片** → 生成多尺寸缩略图（s/m/l）
  - **视频** → FFmpeg 转 H.264 + 抽首帧封面
  - **文档** → Tika 提取文本摘要
- 转码结果回写 `file_meta` + 刷新 Redis 缓存

### 6. 存储策略
- `StorageStrategy` 接口抽象 `upload / download / compose / delete`
- 实现：`MinioStorageStrategy` / `LocalStorageStrategy` / `AliyunOssStorageStrategy`
- 通过 `cloudchunk.storage.type=minio|local|oss` 切换

---

## 快速开始

### 环境要求
- JDK 21+
- Maven 3.9+
- Node.js 20+ / npm 10+
- Docker / Docker Compose

### 启动依赖
```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env.example up -d
```
包含：MySQL 8.0、Redis 7、RocketMQ 5、MinIO

### 初始化数据库
首次启动 `deploy/docker-compose.yml` 时，MySQL 容器会自动执行 `deploy/sql/schema.sql`。如果已经存在旧数据卷，需要手动执行 SQL 或使用 `docker compose -f deploy/docker-compose.yml down -v` 重建环境。

### 运行服务
```bash
mvn -pl cloudchunk-boot -am spring-boot:run -Dspring-boot.run.profiles=dev
```

或打包运行：

```bash
mvn clean package -DskipTests
java -jar cloudchunk-boot/target/cloudchunk-boot.jar --spring.profiles.active=dev
```

### 运行前端
```bash
cd cloudchunk-web
npm ci
npm run dev
```

### 访问
- API: `http://localhost:8080/api/v1`
- 健康检查: `http://localhost:8080/actuator/health`
- 前端: `http://localhost:5173`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- RocketMQ Dashboard: `http://localhost:8180`
- MinIO 控制台: `http://localhost:9003`  (`minioadmin / minioadmin`)

---

## 文档导航

| 文档 | 说明 |
|------|------|
| [文档中心](./docs/README.md) | 文档总入口、推荐阅读路径 |
| [01 架构设计](./docs/01-architecture.md) | 整体架构图、模块拆分、部署拓扑 |
| [02 数据库设计](./docs/02-database-design.md) | MySQL 表结构、Redis Key、索引 |
| [03 API 设计](./docs/03-api-design.md) | RESTful 接口清单、错误码 |
| [04 分片上传协议](./docs/04-chunk-upload-protocol.md) | 分片 / 秒传 / 断点续传 / 合并 时序图 |
| [05 存储策略](./docs/05-storage-strategy.md) | 策略模式设计、Range 下载 |
| [06 异步转码](./docs/06-async-transcoding.md) | RocketMQ 管道、消费者、重试 |
| [07 部署运维](./docs/07-deployment.md) | 环境依赖、docker-compose、监控 |
| [08 工程结构](./docs/08-project-structure.md) | Maven 多模块、包结构规范 |
| [09 性能优化](./docs/09-performance.md) | 上传 / 下载热路径优化、缓存、限流 |
| [10 开发与联调](./docs/10-development-guide.md) | 本地后端、前端、依赖服务启动流程 |
| [11 配置参考](./docs/11-configuration-reference.md) | 环境变量、应用配置、Redis Key、MQ Topic |
| [12 常见问题排障](./docs/12-troubleshooting.md) | 启动、上传、下载、转码问题定位 |
| [13 安全设计](./docs/13-security.md) | 鉴权、授权、资源隔离、防护机制 |
| [14 容错与弹性](./docs/14-resilience.md) | 重试、Watchdog 续期、过期清理、可观测性 |
| [面试资料索引](./docs/interview/README.md) | 项目面试讲解稿与全链路模拟 |

---

## 性能指标（设计目标）

| 指标 | 目标值 |
|------|--------|
| 单文件上传上限 | 50 GB |
| 分片大小 | 默认 10 MB（当前配置 1~200 MB，建议不低于 5 MB） |
| 上传接口 TP99 | ≤ 200 ms（不含分片传输耗时） |
| 断点续传成功率 | > 99% |
| 秒传命中率 | ≈ 35% |
| 转码吞吐（视频 1080p） | 单消费者 ≥ 2 路并行 |
| MD5 秒传查询 TP99 | ≤ 20 ms |

---

## 项目状态

- [x] 架构设计文档
- [x] 核心模块开发
- [x] 分片上传 / 断点续传 / 秒传
- [x] 异步校验 / 转码管道
- [x] MinIO / 本地存储策略
- [x] 前端 Demo
- [x] 本地 docker-compose 依赖环境
- [ ] CI / CD

---

## License

MIT © CloudChunk
