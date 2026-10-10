# M3.6 生产稳定性 P0：告警、发布后验收与受控镜像回滚

> 代码/测试门禁已提交并受 CI 验证；**不等于生产监控已接线或已经执行回滚**。不会自动切流、自动回退数据库、修改用户文件、主动开启公网管理端点。承接 [M3.5](./26-release-readiness.md) 的备份与上线准备。

## 1. 生产监控接线（默认私有、拒绝失败）

原有 `deploy/observability/prometheus.yml` 用于宿主机开发场景；生产叠加 `deploy/docker-compose.prod.example.yml` 后使用 `deploy/observability/prometheus.prod.yml`，从内部 Docker 网络通过 `api:8080/actuator/prometheus` 抓取指标，不经过 Web Nginx 或宿主机发布端口。

配置步骤：

1. 使用凭据系统生成强随机 `METRICS_SCRAPE_TOKEN`（至少 32 个字符，不能复用用户登录令牌）；写入专用主机文件 `METRICS_TOKEN_FILE`，内容必须完全一致，**不要带换行**。生产环境变量文件和凭据文件均不得加入 Git/CI artifacts。
2. `METRICS_TOKEN_FILE` 必须为绝对路径，文件权限不开放给组/其他用户。Linux 上 Prometheus 镜像通常以 UID 65534 运行，可将文件属主设为运行容器的对应 UID 并赋予 `0400` 权限（先核实所使用镜像和 rootless 映射）；Compose 内挂载为只读。确认生产 API 的 `METRICS_SCRAPE_TOKEN` 来自相同环境变量。
3. 设置 `GRAFANA_ADMIN_PASSWORD` 为独立强密码；生产叠加配置禁用 Grafana 匿名登录；Prometheus、Grafana、Jaeger 端口均不向宿主机开放。
4. 运行 M3.5 的 `config_gate.py --mode production --env-file ... --compose-config -`。不满足文件权限、令牌一致性、镜像 digest、非公网暴露规则即阻断。
5. 使用受限网络访问 Prometheus 的 `up{job="cloudchunk-api"}`（应为 1），Grafana 观察 5xx、P95/P99、Hikari、Outbox 老化未完成事件、数据源与目标可用性。

注意：Grafana 的默认密码必须修改、私有监控网络需要独立的访问控制；虽然 Prometheus 没有 host published port，但不应允许非受信任容器进入同一网络。

### 告警规则

| 名称 | 条件 | 初始级别 |
| --- | --- | --- |
| CloudChunkApiScrapeDown | 连续 2 分钟 API 指标不可抓取 | critical |
| CloudChunkApiHigh5xx | 5xx 占比超过 5%，有一定流量且持续 5 分钟 | critical |
| CloudChunkApiP99LatencyHigh | P99 超过 2 秒，持续 10 分钟 | warning |
| CloudChunkHikariConnectionsExhausted | Hikari 等待连接持续 5 分钟 | warning |

阈值只是**默认初始值**，必须依据实际流量和 SLO 调整。规则由 Prometheus 评估，但本仓库**没有配置真实通知渠道**；生产启用前，须连接团队运营的 Alertmanager，配置 PagerDuty/邮件/工单接收人和升级策略，并用受控测试告警确认收到通知。

生产应用如果没有流量，HTTP 延迟与 5xx 指标可能没有样本；不应当把它误判为 0 故障。API `up` 是抓取结果，不代表存储和消息系统每一项正常。M3.6 的独立 MySQL/Redis/RocketMQ/MinIO exporter、远程重试与AI Provider故障指标细化留待 P1。

## 2. 发布后自动验收（只读）

生产部署完成后，从**可信私有运维网络**运行以下命令；`--health-base` 必须直达 API 的 `/actuator/health` 和 `/api/v1/ping`，不能直接用仓库默认的公网 Web Nginx（它不代理 `/actuator/**`）。

```bash
# 从仅有 SELECT 权限的 DB 账号安全注入 MYSQL_HOST/PORT/USER/PASSWORD/DB
python3 deploy/release/post_deploy.py \
  --health-base https://ops.files.example.com \
  --prometheus-base https://prometheus.ops.example.com
```

该脚本首先检查 M3.4/M3.5 的 Flyway V13 成功状态、同目录活跃重名为 0、唯一索引、生成列、API 健康和 Ping，然后调用 Prometheus 即时查询（freshness 不超过 120 秒）：API `up=1`、5 分钟窗口 5xx 比例不高于 5%、P99 不高于 2 秒、MySQL event_outbox 中超过 10 分钟仍处于 pending/publishing/retry 的事件为 0。数据源未连通、无新样本、异常/非数值、DB 不兼容、历史未完成 Outbox 事件、健康失败都返回 JSON `BLOCKED` 并以退出码 2 阻断人工放流。

当请求速率小于 `0.1/s` 时只保留可用性、数据库和 Outbox MySQL 只读检查，输出 `request_metrics=NOT_EVALUATED`，需要额外引流后复验才能宣称性能达标。不会因为低流量而自动回滚或清空数据。

GitHub `M3.6 release and stability gates` 的 `workflow_dispatch`（`live_readonly=true`）使用 `production` Environment 审批、`cloudchunk-release` 私有 self-hosted runner，只读数据库 Secrets 和 `health_base`/`prometheus_base`，执行同样的验收逻辑。它**不会发起部署或自动检测第三方部署已经完成**；接入实际部署编排系统后，可将此脚本作为发布后自动步骤，并以退出码作为切流阻断条件。

## 3. 受控应用镜像回滚（绝不回滚数据库）

`deploy/release/rollback.py` 只操作 API/Worker/Web 镜像，并强制读取两个由组织签批、存放在仓库外的 `0600` JSON 文件，格式如下：

```json
{
  "schema_version": 1,
  "release_sha": "1111111111111111111111111111111111111111",
  "backend_image": "registry.example.com/cloud/api@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "web_image": "registry.example.com/cloud/web@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "schema_compatible_with_current": true,
  "data_contract_compatible_with_current": true
}
```

**示例仅说明字段结构**，实际 SHA、digest 与兼容性声明必须来自已验收的版本记录；当前版本与目标版本的 SHA/镜像不同，当前快照必须与协调备份 `manifest.json` 的 SHA 一致。只有确认数据库 schema 未引入旧版不兼容改动，且发布后的业务写入没有产生旧版本无法读取的数据，才允许将目标文件中的两项兼容性字段设为 `true`。布尔字段是**人工声明，不是自动 Schema 证明**。

```bash
# 预览：验证 release 文件、协调备份 manifest 的 SHA/归档完整性；
# 严格不调用 docker up/down，不触碰真实服务
python3 deploy/release/rollback.py \
  --current-release /secure/releases/current.json \
  --target-release /secure/releases/previous-approved.json \
  --backup-dir /secure/cloudchunk/backups/backup-YYYYMMDD-HHMM \
  --env-file /etc/cloudchunk/production.env

# 操作人取得外部变更审批后执行，要求人工核对镜像与兼容性：
python3 deploy/release/rollback.py \
  --current-release /secure/releases/current.json \
  --target-release /secure/releases/previous-approved.json \
  --backup-dir /secure/cloudchunk/backups/backup-YYYYMMDD-HHMM \
  --env-file /etc/cloudchunk/production.env \
  --apply \
  --ack SCHEMA_AND_DATA_COMPATIBLE \
  --approved-ticket CHANGE-123456 \
  --audit-file /secure/audit/rollback-CHANGE-123456.jsonl \
  --health-base https://ops.files.example.com
```

执行前验证当前 Compose 解析结果与**实际运行的** `cc-api`、`cc-worker`、`cc-web` 镜像和当前 release manifest 一致。利用受限临时配置覆盖 `CLOUDCHUNK_API_IMAGE` 与 `CLOUDCHUNK_WEB_IMAGE`，不改原生产 env 文件；`config_gate` 再次检查全部服务及非目标配置无漂移；只做 `docker compose pull api worker web` 和 `docker compose up -d --no-build --no-deps api worker web`，然后核对实际运行 digest 及私有 HTTPS 健康。写出 `ROLLBACK_STARTED / ROLLBACK_IMAGE_APPLIED / ROLLBACK_INCOMPLETE` 审计事件。

如果拉取/替换/健康失败，则将失败记录在专用审计文件，**不自动执行第二次/反向回滚**；由值班人员判断继续修复或切到 M3.5 的人工数据恢复方案。脚本中的审批单号和兼容性确认**不是身份认证或电子签名**，真正授权必须来自外部变更审批系统与受限机器的执行权限。**禁止**自动 `flyway clean`、`docker compose down -v`、数据库覆盖、绕过 schema 检查。

## 4. 验证与尚未交付的生产动作

```bash
python3 -m unittest discover -s deploy/release/tests -v
python3 -m unittest discover -s deploy/preflight/tests -v
# CI 用真实 Docker + Prometheus promtool 校验规则语法及合成数据恢复
```

M3.6 P0 的 CI 仅验证逻辑、Compose 解析、Prometheus 规则语法、构建及合成数据恢复，不对真实生产运行故障切换、生产消息恢复或流量切换演练。生产启用还需要：外部审批体系、生产 Grafana 账户、Alertmanager 通知接线、私有 CI Runner、镜像 SBOM/签名验证、真实演练记录与按 SLO 校准阈值。Worker 本身无 Web 端口，不在当前 Prometheus API scrape 覆盖范围；Outbox 故障通过只读 SQL 计数纳入发布验收，Worker 指标独立 exporter 为 P1。P1 才扩展组件级 exporter、故障注入、运维控制台与灾备 RPO/RTO 量化。
