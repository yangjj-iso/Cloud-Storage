# CloudChunk 文档中心

本文档集以仓库当前源码、`application.yml`、`deploy/docker-compose.yml`、数据库脚本和前端请求封装为事实源。它描述的是当前版本可运行或可调用的行为，不将设计设想、尚未接线的页面或未保存压测报告的数字视为已交付能力。

## 术语与状态

- **已实现**：后端或前端存在可执行代码，并且能从配置、路由或调用点追溯。
- **部分接线**：后端 API 已提供，但当前 Web 页面未覆盖完整用户流程。
- **扩展预留**：接口、枚举或配置位置存在，但没有可运行实现。
- **可复现指标**：附带脚本、输入、环境和输出来源；没有这些信息的性能描述只作为设计边界。

## 开始前

完整本地启动步骤、端口和依赖边界见根目录 [README](../README.md)。需要特别注意：

- `deploy/docker-compose.yml` 默认启动基础设施；`--profile app` 会同时启动 API、Worker 和 Web。
- 后端默认端口和 Vite 开发代理目标均为 `8080`。
- OpenAPI 页面由运行中的后端提供：`/swagger-ui.html`。

## 阅读路径

### 运行与验证

| 文档 | 说明 |
| --- | --- |
| [根 README](../README.md) | 环境要求、依赖启动、端口和前后端启动命令 |\n| [M3.5 正式发布准备](./26-release-readiness.md) | 配置门禁、备份/恢复演练、生产审批和回滚手册 |
| [M3.6 生产稳定性](./27-production-stability.md) | 私有指标抓取、告警规则、发布后 SLO 门禁、安全回滚 |
| [08 基准测试与量化边界](./08-benchmark-metrics.md) | JMH、k6、Shell 脚本与指标解释 |
| [前端 README](../cloudchunk-web/README.md) | Web 功能覆盖、开发代理和构建 |
| [压测脚本 README](../deploy/bench/README.md) | k6/Shell 命令、认证和环境变量 |

### 架构与核心链路

| 顺序 | 文档 | 关注点 |
| --- | --- | --- |
| 1 | [00 整体架构](./00-architecture.md) | 模块职责、依赖方向、运行时组件和事实边界 |
| 2 | [01 分片上传](./01-upload-flow.md) | 初始化、预签名直传、代理降级、确认、合并与自动合并 |
| 3 | [02 秒传与断点续传](./02-instant-resume-flow.md) | 内容复用、共享会话、分片去重和进度重建 |
| 4 | [03 校验与媒体处理](./03-checksum-transcode-flow.md) | 文件状态、RocketMQ、缩略图、封面和补偿 |
| 5 | [04 下载与缓存](./04-download-flow.md) | 活跃引用鉴权、预签名 URL、Range 代理与缓存 |
| 6 | [05 限流、清理与存储](./05-ratelimit-cleanup-flow.md) | Redis Lua、上传会话过期和存储策略 |
| 7 | [06 网盘、分享与配额](./06-drive-share-quota-flow.md) | 逻辑文件、回收站、ZIP、公开分享和预留配额 |
| 8 | [07 认证、管理与可观测性](./07-auth-admin-observability-flow.md) | Redis 会话、RBAC、预览、WebSocket 边界和 Actuator |
| 9 | [09 架构加固](./09-architecture-hardening.md) | 模块边界、Outbox/Inbox、Flyway、API/Worker 拆分和验证门禁 |
| 10 | [10 可观测性与链路追踪](./10-observability.md) | Prometheus、Grafana、Jaeger、指标和异步 Trace 传播 |

## 前端覆盖矩阵

| 能力 | 后端 | 当前 Web 页面 |
| --- | --- | --- |
| 登录、注册、找回密码 | 已实现 | 已接入 |
| 上传、秒传、续传、取消/重试 | 已实现 | 已接入 |
| 文件列表、预览、下载、配额 | 已实现 | 已接入 |
| 目录、回收站、我的分享 | 已实现 | 已接入 |
| 管理用户、角色、密码、配额、文件和设置 | 已实现 | 已接入 |
| 公开分享浏览、公开下载、转存 | 已实现 | 已接入 |
| 目录 ZIP 下载、修改邮箱 | 已实现 | 已接入 |
| 上传 WebSocket 进度 | 后端处理器已实现 | 已接入 |
| IndexedDB 冷启动任务恢复 | 上传状态会写入 | 未实现自动恢复 |

## 文档维护规则

更新功能时同步维护对应专题，并至少核对以下内容：

1. Controller 路由与 DTO 是否改变。
2. `application.yml` 的默认值、环境变量或端口是否改变。
3. 前端 `api.ts`、上传流程或页面是否新增/移除接线。
4. Compose 服务、SQL 初始化或压测脚本是否改变。
5. 是否有新的可保存基准输出；没有输出时不写成实测性能结论。
