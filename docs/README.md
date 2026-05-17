# CloudChunk 文档中心

> 这里是 CloudChunk 的工程文档入口，按“先跑起来、再理解架构、最后深入链路”的顺序组织。

## 快速入口

| 场景 | 推荐阅读 |
|------|----------|
| 第一次运行项目 | [10 开发与联调指南](./10-development-guide.md) |
| 配置本地 / 测试 / 生产环境 | [11 配置参考](./11-configuration-reference.md) |
| 服务启动失败或上传下载异常 | [12 常见问题排障](./12-troubleshooting.md) |
| 理解整体架构 | [01 架构设计](./01-architecture.md) |
| 对外接口对接 | [03 API 设计](./03-api-design.md) |
| 准备面试讲解 | [interview 面试资料索引](./interview/README.md) |

## 主线文档

| 序号 | 文档 | 说明 |
|------|------|------|
| 01 | [架构设计](./01-architecture.md) | 整体架构、模块拆分、部署拓扑、演进方向 |
| 02 | [数据库与缓存设计](./02-database-design.md) | MySQL 表结构、Redis Key、索引与一致性模型 |
| 03 | [API 设计](./03-api-design.md) | REST 接口、统一响应、错误码、OpenAPI |
| 04 | [分片上传协议](./04-chunk-upload-protocol.md) | 秒传、分片上传、断点续传、合并、异步校验 |
| 05 | [存储策略](./05-storage-strategy.md) | MinIO / 本地磁盘 / OSS 的策略模式抽象 |
| 06 | [异步转码管道](./06-async-transcoding.md) | RocketMQ Topic/Tag、图片/视频/文档消费者、重试 |
| 07 | [部署与运维](./07-deployment.md) | docker-compose、本地端口、监控、容量规划 |
| 08 | [工程结构与命名规范](./08-project-structure.md) | Maven 多模块、包结构、编码与 Git 规范 |
| 09 | [性能优化与并发设计](./09-performance.md) | 虚拟线程背压、Redis Lua、缓存、Range 下载 |
| 10 | [开发与联调指南](./10-development-guide.md) | 后端、前端、依赖服务的本地启动流程 |
| 11 | [配置参考](./11-configuration-reference.md) | 环境变量、应用配置项、Topic、Redis Key |
| 12 | [常见问题排障](./12-troubleshooting.md) | 启动、依赖、上传、下载、转码的排查路径 |

## 资料补充

| 目录 / 文件 | 说明 |
|-------------|------|
| [interview/](./interview/README.md) | 按模块拆分的项目面试讲解稿 |
| [images/](./images/) | 文档与示例文章用到的 GIF / 图片资源 |
| [纯小白如何在 GitHub 上贡献代码](./blog-纯小白如何在GitHub上贡献代码.md) | 面向新手的 GitHub 贡献流程文章 |

## 推荐阅读路径

### 开发者

1. [10 开发与联调指南](./10-development-guide.md)
2. [03 API 设计](./03-api-design.md)
3. [04 分片上传协议](./04-chunk-upload-protocol.md)
4. [08 工程结构与命名规范](./08-project-structure.md)

### 运维 / 部署

1. [07 部署与运维](./07-deployment.md)
2. [11 配置参考](./11-configuration-reference.md)
3. [12 常见问题排障](./12-troubleshooting.md)
4. [09 性能优化与并发设计](./09-performance.md)

### 面试复盘

1. [01 项目概览与架构](./interview/01-项目概览与架构.md)
2. [03 分片上传](./interview/03-分片上传.md)
3. [06 服务端合并](./interview/06-服务端合并.md)
4. [13 面试全链路模拟](./interview/13-面试全链路模拟.md)

## 文档维护约定

- 文档中的端口、环境变量、接口路径应以代码和 `deploy/docker-compose.yml` 为准。
- 新增后端配置时，同步更新 [11 配置参考](./11-configuration-reference.md)。
- 新增接口时，同步更新 [03 API 设计](./03-api-design.md) 或在后续版本中补充 OpenAPI 示例。
- 修改部署脚本时，同步更新 [07 部署与运维](./07-deployment.md) 与 [12 常见问题排障](./12-troubleshooting.md)。
