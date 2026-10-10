# CloudChunk

CloudChunk 是一个面向文件管理场景的 Java 服务，提供分片上传、断点续传、内容去重、对象存储下载、网盘目录、分享与配额管理。项目由 Spring Boot 后端和 React 前端组成；开发环境中的 MySQL、Redis、RocketMQ 与 MinIO 由 Docker Compose 提供。

> 本文只描述仓库当前可验证的实现。性能数字应以 [基准测试说明](./docs/08-benchmark-metrics.md) 中的脚本、环境与输出为准。

## 已实现能力

- 分片上传：支持 `multipart/form-data` 后端代理上传，以及 MinIO 预签名直传后确认分片。
- 上传恢复：上传会话、Redis 分片进度、MySQL 分片记录和对象存储清单共同支撑续传重建。
- 内容去重：整文件 MD5 秒传、同一运行中会话复用，以及分片级服务端复制。
- 合并与校验：MinIO Compose 合并后异步校验整文件 MD5；校验成功后文件才进入 `AVAILABLE` 状态。
- 媒体处理：图片生成多尺寸缩略图，视频抽取封面，文档提取文本信息。视频当前不执行 H.264 重编码。
- 文件管理：目录、移动、重命名、回收站、ZIP 打包、下载、预览、分享和转存。
- 账号治理：Bearer Token 会话、邮箱验证码、管理员用户/文件/配额/设置管理。
- 基础治理：限流、过期上传清理、Caffeine + Redis 文件元数据缓存、Actuator 指标和可配置 OTLP 采样。
- 可观测性：Prometheus 指标、Grafana 预置总览、Jaeger 链路追踪，以及贯穿 HTTP、Outbox、RocketMQ、校验和转码的 Trace ID。

## 当前边界

- 已实现的存储策略是 `minio` 和 `local`。`oss` 是枚举与扩展预留，不是可运行实现。
- `deploy/docker-compose.yml` 默认只启动基础设施；启用 `app` profile 可同时启动 API、Worker 和前端静态站点。
- 前端已接入上传、文件、网盘、回收站、公开及个人分享、目录 ZIP 下载、邮箱换绑和完整管理页面，所有用户界面统一使用中文。
- 后端上传 WebSocket 已接入 Web 前端，前端会同时将上传状态写入 IndexedDB；冷启动后不会自动恢复任务，但重新选择原文件后可按后端可信进度续传。

## 技术栈

| 层次 | 当前实现 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.3.4、Tomcat、Virtual Threads |
| 数据访问 | MySQL 8、MyBatis-Plus 3.5.7 |
| 缓存与限流 | Redis 7、Lua、Caffeine |
| 文件存储 | MinIO、本地文件系统 |
| 异步处理 | Apache RocketMQ 5 |
| 文件处理 | FFmpeg、Thumbnailator、Apache Tika |
| 前端 | React 18、TypeScript、Vite、Tailwind CSS |
| 构建与运行 | Maven、npm、Docker Compose |

## 仓库结构

```text
cloudchunk-api/         REST、过滤器与 WebSocket 入口
cloudchunk-contract/    跨模块事件与持久化任务端口
cloudchunk-core/        上传、文件、网盘、分享、配额、认证等业务
cloudchunk-storage/     MinIO / 本地存储策略
cloudchunk-mq/          Outbox 发布器与 RocketMQ 消费适配器
cloudchunk-transcode/   幂等转码消费者与独立死信处理
cloudchunk-infra/       Redis、Outbox/Inbox、对象 GC 与基础配置
cloudchunk-boot/        Spring Boot 启动、Flyway 与 API/Worker profile
cloudchunk-web/         React 前端
cloudchunk-benchmark/   JMH 微基准
deploy/                 Compose、数据库初始化、压测脚本
docs/                   架构、业务链路与验证文档
```

## 本地启动

### 前置条件

- JDK 21
- Maven 3.9+
- Node.js 20+ 和 npm
- Docker Desktop 或可用的 Docker Compose
- 可选：FFmpeg。视频封面任务需要命令可通过 `FFMPEG_PATH` 找到。

### 1. 启动基础设施

复制配置文件并按需修改密码：

```powershell
Copy-Item deploy/.env.example deploy/.env
```

启动 MySQL、Redis、RocketMQ 和 MinIO：

```powershell
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
```

数据库结构由 Flyway 单一管理：空数据库执行 `V1` 起的全部迁移，已有旧库以版本 `1` 为基线后执行 `V2+`。`deploy/sql/schema.sql` 仅保留为旧环境参考，不再由 Compose 自动导入，避免双份 Schema 漂移。

如需直接启动包含 API、Worker 和前端的完整容器栈：

```powershell
docker compose --profile app -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

如需启动 Jaeger、Prometheus 与 Grafana：

```powershell
docker compose --profile observability -f deploy/docker-compose.yml --env-file deploy/.env up -d jaeger prometheus grafana
```

### 2. 启动后端

```powershell
mvn -pl cloudchunk-boot -am spring-boot:run -Dspring-boot.run.profiles=dev
```

或先打包：

```powershell
mvn clean package -DskipTests
java -jar cloudchunk-boot/target/cloudchunk-boot.jar --spring.profiles.active=dev
```

默认开发配置连接 `127.0.0.1:3308` 的 MySQL、`127.0.0.1:6380` 的 Redis、`127.0.0.1:9876` 的 RocketMQ 和 `127.0.0.1:9002` 的 MinIO。可通过 `application.yml` 中列出的环境变量覆盖。

### 3. 启动前端

```powershell
Set-Location cloudchunk-web
npm ci
npm run dev
```

前端开发服务默认是 `http://localhost:5173`，`vite.config.ts` 已将 `/api` 代理到默认后端端口 `http://localhost:8080`。

## 本地地址与端口

| 服务 | 地址 | 说明 |
| --- | --- | --- |
| 后端 API | `http://localhost:8080/api/v1` | 默认 Spring Boot 端口 |
| OpenAPI UI | `http://localhost:8080/swagger-ui.html` | 运行后由 springdoc 提供 |
| 健康检查 | `http://localhost:8080/actuator/health` | Actuator |
| Prometheus 指标 | `http://localhost:8080/actuator/prometheus` | Actuator（需管理员 Bearer Token） |
| 前端开发服务器 | `http://localhost:5173` | Vite |
| MySQL | `localhost:3308` | Compose 映射 |
| Redis | `localhost:6380` | Compose 映射 |
| RocketMQ NameServer | `localhost:9876` | Compose 映射 |
| RocketMQ Dashboard | `http://localhost:8180` | Compose 映射 |
| MinIO S3 API | `http://localhost:9002` | Compose 映射 |
| MinIO Console | `http://localhost:9003` | 默认账号见 `deploy/.env` |
| Grafana | `http://localhost:3000` | 自动加载 CloudChunk 运行总览 |
| Jaeger | `http://localhost:16686` | HTTP 与异步消息链路查询 |
| Prometheus | `http://localhost:9090` | 指标查询与抓取状态 |

## 文档导航

- [文档中心](./docs/README.md)：阅读顺序、功能边界和验证入口。
- [整体架构](./docs/00-architecture.md)：模块职责、依赖方向与运行时数据流。
- [上传流程](./docs/01-upload-flow.md)：初始化、分片、直传确认、合并和自动合并。
- [秒传与续传](./docs/02-instant-resume-flow.md)：会话复用、分片去重和进度重建。
- [校验与媒体处理](./docs/03-checksum-transcode-flow.md)：文件状态、RocketMQ 和补偿任务。
- [下载与缓存](./docs/04-download-flow.md)：授权、预签名 URL、Range 代理和缓存失效。
- [限流与清理](./docs/05-ratelimit-cleanup-flow.md)：令牌桶、上传会话状态机与存储扩展点。
- [网盘、分享与配额](./docs/06-drive-share-quota-flow.md)：逻辑文件、回收站、分享和预留模型。
- [认证、管理与可观测性](./docs/07-auth-admin-observability-flow.md)：会话、权限、预览、WebSocket 边界和运行指标。
- [基准测试](./docs/08-benchmark-metrics.md)：可复现命令和指标解释。
- [架构加固说明](./docs/09-architecture-hardening.md)：模块边界、可靠事件、迁移、运行时拆分与验证。
- [可观测性与链路追踪](./docs/10-observability.md)：指标、日志、Jaeger 以及异步 Trace 上下文传播。
- [AI 证据问答](./docs/12-ai-grounded-answer.md)：可选模型配置、单次授权、引用验证和使用边界。
- [混合检索 M2.2](./docs/13-ai-hybrid-retrieval.md)：用户授权的 Embedding 索引、混合排序与当前限制。
- [AI 一步问答](./docs/14-ai-one-step-ask.md)：明确授权后自动检索少量来源并生成引用回答。
- [AI 每日调用预算](./docs/15-ai-daily-call-budget.md)：按 UTC 日和用户对模型请求实施数据库原子配额、用量看板与后台延期重试。
- [Qdrant 向量镜像](./docs/16-qdrant-vector-mirror.md)：可选加速、异步同步、权限复核与故障回退。
- [Qdrant 真容器测试与索引健康](./docs/17-qdrant-health-evaluation.md)：真实 Qdrant 1.15 回归、检索合成基准和用户级镜像状态。
- [AI Token 用量与中文检索评测](./docs/18-ai-evaluation-and-usage.md)：提供方用量、费用估算、延迟分位和基准脚本。
- [受控 AI 文件操作 M2.8](./docs/19-ai-confirmed-rename.md)：基于原文生成建议、预览差异、二次确认重命名与审计。
- [受控 AI 文件归类 M2.9](./docs/20-ai-confirmed-move.md)：用户授权、已存在目录推荐、二次确认移动与权限回归。
- [AI 整理体验 M3.0](./docs/21-ai-folder-shortlist-history.md)：自主选择文件夹候选、同名冲突预检与历史记录面板。
- [AI 批量预览与条件撤销 M3.1](./docs/22-ai-batch-preview-and-undo.md)：最多四份文件批量建议、逐条批准与15分钟条件撤销。
- [统一目录命名 M3.2](./docs/23-drive-sibling-name-invariants.md)：MySQL 活跃文件名唯一约束、冲突反馈、上传及转存重名后缀、并发回归。
- [网盘冲突与操作审计 M3.3](./docs/24-drive-audit-conflict-ui.md)：用户级操作记录、固定审计分类和冲突提示。
- [M3.4 上线预检与验收](./docs/25-release-preflight-and-acceptance.md)：只读历史重名/Flyway/健康检查、真实迁移阻断回归和隔离账号端到端验收。

## 验证

后端单元测试：

```powershell
mvn test
```

完整校验（含 ArchUnit；Docker 可用时还会跑 Testcontainers 集成测试：真实 Redis 上的上传进度并发语义、真实 MySQL 上的配额并发与合并/取消加锁顺序，以及空库执行全部 Flyway 迁移）：

```powershell
mvn verify
```

没有 Docker 时集成测试会明确跳过，单元测试与架构约束仍然执行。

> Docker Desktop 29 及以上版本在 Windows 上需要显式指定 API 版本，否则 Testcontainers 探测守护进程会收到 400 而把集成测试判为跳过：
>
> ```powershell
> mvn verify "-Dapi.version=1.44"
> ```

前端构建：

```powershell
Set-Location cloudchunk-web
npm run build
```

性能验证入口见 [docs/08-benchmark-metrics.md](./docs/08-benchmark-metrics.md)。未保存运行环境与输出的数字不应作为实测结论。

## License

MIT © CloudChunk
