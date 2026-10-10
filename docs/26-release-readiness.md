# M3.5 正式发布准备：自动化门禁、备份恢复与回滚

> **状态：工具与运行手册已提交；不代表已完成生产发布或生产灾备演练。** 本阶段没有生产凭据、没有生产数据库操作、没有自动恢复或自动发布。

## 1. 门禁矩阵（失败必须停止）

| 阶段 | 执行位置 | 通过条件 | 失败动作 |
| --- | --- | --- | --- |
| PR / main / v* | GitHub Actions `M3.5 release gates` | Maven verify、npm build、检索评测、Python 预检与恢复工具单测、基础 Compose 语法、生产覆盖文件样例验证 | 不合并/不打标签 |
| 生产配置审批 | 已授权部署主机 | `config_gate.py --mode production` 通过，Compose 解析后镜像均为 sha256 不可变引用、API/Worker 同版本、管理端口没有发布，Web 只在 loopback | 不进行任何部署 |
| 迁移前 | 生产只读账号、可信网络 | `check_release.py --phase before-v13` 通过 | 阻断部署；人工处理历史重名 |
| 备份与隔离恢复 | 维护窗口 / 同平台隔离环境 | 完整备份清单和 SHA-256 校验通过、真实 MySQL SQL 恢复/表结构验证通过、对象及消息存储归档可在隔离卷还原 | 保留维护窗口，不推进发布 |
| 发布后 | 可信网络 | `check_release.py --phase ready --health-base https://...` 通过；人工关键业务检查通过 | 冻结写入并启动回滚决策 |
| 受保护的线上只读门禁 | GitHub Actions 手动触发 | 生产 Environment 审批 + 环境 Secrets + MySQL V13/HTTP 健康通过 | 阻断手动放行，不会自动部署 |

**仓库的 CI 成功不能替代线上审批。** 在 GitHub 仓库设置中为 `main` 配置 branch protection/ruleset：要求 `config and backup contract tests` 和 `backend and frontend build` 等检查成功，禁止未通过门禁的合并。为 `production` Environment 设定 required reviewers、允许部署的分支/标签，并配置仅具有 SELECT 的数据库账号。仓库工作流不能自行保障这些 GitHub 管理侧策略已经开启。

## 2. 生产配置与镜像锁定

基础 `deploy/docker-compose.yml` 是**开发环境模板**：存在 root/minioadmin 默认值、`latest` 镜像、直通容器端口等；**不可直接用于公网生产**。

1. 将 `deploy/.env.production.example` **复制到仓库外**，例如 `/etc/cloudchunk/production.env`，使用凭据管理系统注入两个不同且足够长的密码、MinIO 非默认用户名及 HTTPS WS Origin。限制文件权限（Linux `chmod 600`），绝不能提交。
2. 为模板所有 `*_IMAGE` 变量赋值，引用完整 `registry/image@sha256:<64 hex>`。应用 API/Worker 使用相同 SHA，Web 使用另一已构建和扫描的 SHA。不要只使用 `latest`、`stable` 或浮动 tag；上线前记录 SBOM、镜像构建来源与镜像签名验证结果（当前脚本只验证 digest 格式，不验证签名）。
3. Docker Compose 需支持 `!reset` 和 `!override`（建议 v2.24.4+）。下例直接将解析后的 JSON 通过管道送入校验器，**不要重定向到会被上传到 CI 的文件**。

```bash
set -euo pipefail
ENV_FILE=/etc/cloudchunk/production.env
docker compose --env-file "$ENV_FILE" \
  -f deploy/docker-compose.yml -f deploy/docker-compose.prod.example.yml \
  --profile app --profile vector --profile observability config --format json |
  python3 deploy/release/config_gate.py --mode production \
    --env-file "$ENV_FILE" --compose-config -
```

该配置校验会拒绝弱密码/默认账号、非 HTTPS Origin、意外启用的公网 Prometheus、未经审批的跨用户秒传，以及开启但未提供合规 HTTPS 配置的 AI 后端；检查最终 Compose 中任何**敏感服务端口的发布**、Web 非 loopback 映射、可变镜像、构建指令和 API/Worker 镜像漂移。生产环境必须使用 TLS 终止的可信反向代理连接 Web loopback 端口；请单独设置入口层 TLS、WAF/速率限制、访问日志与身份认证。

**边界：**这不是实时连接测试；不验证证书到期日、镜像供应链签名、宿主机防火墙、云防火墙、外部远程写入者、存储容量及备份加密强度。配置失败不允许 `--force` 绕过。

## 3. 协调备份：MySQL + 原始对象 + 运行态存储

`snapshot.py` 用标准 Python 库和 Docker CLI 生成：

- `cloudchunk.sql`：MySQL 8 的完整 `cloudchunk` schema/data 逻辑快照（包含 Flyway、Outbox/Inbox 等持久表，使用 `--single-transaction`）。
- `minio-data.tar.gz`：原始对象存储卷，**不能只备数据库**。
- `redis-data.tar.gz`：包含会话等运行态数据。恢复后会话可能仍需重新登录。
- `rmq-store.tar.gz`：RocketMQ broker 本地文件；消息恢复后仍须人工评估去重、消息重放与积压。
- `qdrant-data.tar.gz`：如果卷存在则附带；使用 AI 向量服务时必须添加 `--require-qdrant`。
- `manifest.json`：40 位提交 SHA、时间、文件大小、SHA-256 和覆盖范围。

**维护先决条件**：从负载均衡摘流，禁止新上传/写入，停掉所有 API/Worker/Web 实例（包括其他机器上的实例和旁路作业），停止 MinIO、Redis、RocketMQ broker、Qdrant，然后确认写入流量确已静止。MySQL 必须保持运行供逻辑导出。脚本会检查同一 Compose 项目内运行中的服务，但**无法识别其他主机上的写入者**，操作人必须另行确认。

```bash
# 在有权限访问目标 Compose 项目的专用主机上、约定的维护窗口中执行
docker compose -p cloudchunk -f deploy/docker-compose.yml \
  --profile app --profile vector stop web api worker minio redis rmq-broker qdrant

# 输出目录必须是仓库外、不存在的受限路径；文件内容含真实用户数据
python3 deploy/release/snapshot.py backup \
  --project cloudchunk \
  --output-dir /secure/cloudchunk/backups/backup-YYYYMMDD-HHMM \
  --release-sha "$(git rev-parse HEAD)" \
  --maintenance-ack WRITERS_STOPPED
# 启用 Qdrant 时，在上述命令末尾额外加 --require-qdrant
```

**备份不自动启停生产服务，不自动恢复生产流量。** 发生失败，带有不完整文件的输出目录不能作为备份；只有生成 manifest 的备份可进入恢复演练。备份材料必须离机、加密、限制读取权限并设定保留/轮换策略；SHA-256 清单只检查完整性，不能代替离机存储、加密或独立签名。记录维护窗口开始/结束、实例范围、备份目录、审批人、对象数量和校验结果（不记录密码、真实文件名或文件正文）。

## 4. 隔离恢复演练（禁止面向生产 volume）

```bash
python3 deploy/release/snapshot.py verify \
  --backup-dir /secure/cloudchunk/backups/backup-YYYYMMDD-HHMM
python3 deploy/release/snapshot.py drill \
  --backup-dir /secure/cloudchunk/backups/backup-YYYYMMDD-HHMM \
  --disposable-ack CREATE_ISOLATED_DOCKER_VOLUMES
```

`verify` 校验清单中的完整 SHA-256、文件大小、归档合法性和路径遍历风险；失败直接阻断。 `drill` 在本机创建**随机命名的新 Docker volumes**、使用 `--network none` 启动**无对外端口**的临时 MySQL 8，导入 SQL，确认 `user_file` 和 `flyway_schema_history` 存在，并把 MinIO/Redis/RocketMQ/Qdrant 归档还原进隔离卷，检查 MinIO 卷存在文件。结束或失败时自动移除**仅它自己创建**的容器与卷。不得在装有生产数据的环境中以改名、软链接等方式将其临时卷名称指向生产卷。

此演练证明 SQL 能导入和卷归档能物理还原，**尚未证明 MinIO 对象 API 的逐字节可读性、消息重放正确性、Qdrant 索引语义、Redis 会话兼容性或跨版本业务兼容**。在独立测试环境进一步用独立权限账号完成真实文件上传/下载校验、RocketMQ 消费与 AI 索引回归，并记录 RPO、RTO、数据校验数量；没有实际测量不能宣布灾备 SLA 达标。

## 5. 手动发布 SOP

1. PR 合并前确保所有要求的检查为绿色，记录待上线 SHA、发布说明、迁移清单、镜像 digest、SBOM/漏洞结果和回滚负责人。
2. 完成生产配置检查、只读 `before-v13` 预检。存在活跃同名冲突或失败 Flyway 历史立即停止。
3. 约定维护窗口，冻结写入，执行协调备份、`verify` 和 `drill`；**演练成功才允许继续**。需要恢复 Redis/RocketMQ/Qdrant 的环境，必须同时规划服务级验证。
4. 采用生产 Compose 覆盖文件并**以保存的 digest** 拉取部署，不在生产编译。例如在受控部署主机执行：

```bash
docker compose --env-file /etc/cloudchunk/production.env \
  -f deploy/docker-compose.yml -f deploy/docker-compose.prod.example.yml \
  --profile app --profile vector pull
docker compose --env-file /etc/cloudchunk/production.env \
  -f deploy/docker-compose.yml -f deploy/docker-compose.prod.example.yml \
  --profile app --profile vector up -d --no-build
```

5. 从可信网络运行 `python3 deploy/preflight/check_release.py --phase ready --health-base https://files.example.com`。使用**临时专用账号**执行 M3.4 的 `drive_smoke.py`（不得用生产用户账号），另做人工文件上传、下载字节比对、后台队列、仪表盘、Redis Session、MinIO 和 Qdrant（启用时）可用性复核。
6. 手工完成发布批准及流量恢复，持续观察健康状态、5xx、延迟、MySQL 连接、对象错误率、队列堆积、Outbox/Inbox 和 AI 提供商故障。记录可观测数据时间窗口和实际结果。

健康预检的 HTTPS 入口必须是**仅受信任运维网络可访问的私有网关**，将 `/actuator/health` 与 `/api/v1/ping` 路由至后端 API；仓库自带 Web Nginx 只转发 `/api/`，不能直接将 Web 公网入口作为 `--health-base`。不要为了使检查通过而向公网开放 `/actuator/**`。

GitHub `workflow_dispatch` 的 `live_readonly=true` 会在保护的 `production` Environment 下执行**只读** MySQL V13 和 HTTPS 健康预检；须在环境中提供 `RELEASE_MYSQL_HOST/PORT/USER/PASSWORD/DB` Secret（只读账号）及手动输入 `health_base`。该工作流没有生产部署/迁移权限。

## 6. 回滚操作手册（人工审批、先选正确路径）

**启动条件：** 发布后核心健康不达标、对象缺失/校验失败、数据库错误上升、消息大量无法消费、认证大面积失败、AI 受控写操作越权或数据一致性异常。立即停止新流量与写入、保留现场日志、标记失败发布 SHA、告知值班责任人；不要盲目回滚数据库。

**路径 A：应用镜像回退（无不兼容 schema 迁移且新版本尚未写入不可被旧版识别的数据）**

- 比对上一版本与当前版本 DB schema/事件契约，确认旧版 API/Worker 可同时读取最新数据。涉及 Flyway V13 等不可逆结构变更时，**不得**直接回退旧应用。
- 在受控配置中将 API/Worker/Web 的镜像 digest **一起**改回已签名/已验收的上一组；重复运行配置校验后，执行 `docker compose ... pull` 和 `docker compose ... up -d --no-build`。不要使用 `docker compose down -v`。
- 从可信网络执行 `check_release.py --phase ready`（适用时）、健康与人工字节校验；旧版不能理解当前 schema 时立即转路径 B 或热修复前向迁移。

**路径 B：数据恢复（破坏性、需要单独变更单与双人审批）**

- 停止所有写入者/消费者，封存故障时刻数据库和 MinIO 等卷的**额外**证据副本，以免丢失发生在原备份之后的数据。
- 明确最后一个通过 `verify` 和 `drill` 的协调备份及其 `release_sha`；在**全新隔离环境**先进行服务级读写验证和恢复顺序测试（MySQL + 原始对象必须是同一窗口的配对）。
- 评估备份之后的写入损失、分享链接/用户会话影响、消息去重和 AI 索引重建，确定实际 RPO/RTO；完成业务与安全负责人确认后再使用组织运维流程将数据导回生产。**仓库没有自动覆盖/清空生产卷的恢复命令**，防止意外毁坏。
- 恢复后核验 Flyway 状态、用户文件引用与实际对象数量/字节、权限、幂等消息、审计记录，并按批准顺序重新放流。记录失败原因和恢复效果。

**严禁：** `flyway clean`、直接删除 V13 唯一索引、`docker compose down -v`、以旧 SQL 覆盖还在写入的新库、恢复数据库而不恢复对应 MinIO 对象、只因 HTTP 200 就宣布灾备通过。

## 7. 交付与待补项

本阶段交付可执行配置门禁、GitHub 只读发布门禁、备份/验证/隔离恢复工具、模拟单元测试、使用合成数据的真实 Docker 恢复 CI 及此 SOP。**需要人工配置** GitHub 保护规则与 Environment、机密配置、签名镜像、生产级 TLS/防火墙/容量，以及生产演练窗口。真正的跨机房副本、全自动零停机切流、数据库增量/PITR、外部对象存储复制、MinIO API 语义/消息重放全量演练仍是后续工作，不能把当前工具当作已实现能力。
