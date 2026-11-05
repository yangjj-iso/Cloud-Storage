# M3.8：生产 SLO 与故障处置闭环

> **交付边界：**管理员只读 SLO / 活动告警视图、受限单操作员事件状态机与追踪记录、CI 与验收手册。本阶段没有自动关闭生产告警、自动重启、数据库写入式事件表或自行实施回滚。对真实生产环境的监控接入及值班流程仍需单独配置和批准。

## 1. 运维控制台入口

管理员登录后进入「管理后台 → 依赖状态」，除了 M3.6 依赖诊断外，可查看 M3.8 的 SLO 和活动告警卡片。数据通过：

```http
GET /api/v1/admin/operations
Authorization: Bearer <管理员身份令牌>
```

该端点被已有 `/api/v1/admin/**` 角色规则保护，响应 `Cache-Control: no-store`。它只读取配置好的 Prometheus 即时查询与 Alertmanager 活动告警，不向浏览器返回 Prometheus URL、Webhook 地址、令牌、原始标签、文件信息或异常消息。来源未配、网络断开、数据超时会返回 `UNCONFIGURED` / `UNAVAILABLE`，**不会错误展示绿色**。

在生产部署的 Compose 覆盖配置中，API 内网可通过 `OPS_PROMETHEUS_URL=http://prometheus:9090` 和 `OPS_ALERTMANAGER_URL=http://alertmanager:9093` 获取状态；配置门禁仅允许这两个明确的内部地址，不接受前端提供任意 URL，也不通过公网暴露管理端点。这是读权限，Prometheus/Alertmanager API 的服务间网络授权仍要由运维进一步限制。

### SLO 计算和解释

当前是 **初始工程观察指标**，目标 7 日 HTTP 成功率 99.9%，以及 1 小时错误预算燃烧速度。不将其写成合同或 SLA 保证。

- 7 日观察可用率 = 1 − `7d HTTP 5xx 请求数 / 7d HTTP 总请求数`。请求数来自 Micrometer 中的 `http_server_requests_seconds_count`。
- 1 小时错误预算燃烧 = `1h HTTP 5xx / 1h HTTP 总请求 / 0.001`。燃烧值为 1× 表示达到 99.9% 目标对应的错误比例。
- `count_over_time(up{job="cloudchunk-api"}[7d])` 与 15 秒期望抓取间隔估算数据覆盖比例；覆盖率不足 90% 时状态 `WARMING`。此值反映**已采集的时序样本数量**，不是服务的真实运行时间。
- 7 日请求少于 100 时标注 `LOW_TRAFFIC`，不据此宣布 SLO 达标。窗口有效、请求量足够且错误率高于预算时 `BREACH`；其余只是 `OBSERVED`，**不等于端到端完整可用性**。
- Prometheus 查询必须有唯一的新鲜样本（≤120 秒）、数值有限且非负；异常一律 `UNAVAILABLE`。

**重要限制：**没有进入应用的请求、DNS/反向代理故障、直接依赖故障和采集器停机均可能不体现在 HTTP 请求 5xx 统计中；本阶段不能仅凭该指标签署业务 SLO。真实业务 SLI 需要独立合成用户请求、外部探针及数据完整性验证。

活动告警从 Alertmanager 查询，最多展示 20 条；只允许已知的四类应用告警名称及 `critical/warning` 级别、受限指纹和时间，忽略其余不可信标签和注释。**没有活动告警不代表历史上没有故障**。

## 2. 故障事件生命周期（只能离线变更）

为防止管理页面拥有危险的运维写权限，事件确认/缓解/恢复/复盘仅允许在可信单操作员运维主机执行：

```bash
umask 077
# 每个事件使用新的、仓库外的私有 JSONL 文件，目录需要预先创建。
JOURNAL=/secure/incidents/INC-20261011A.jsonl
BASE='--incident-id INC-20261011A --service api --ticket CHANGE-123456'
python3 deploy/resilience/incident.py open \
  --journal "$JOURNAL" $BASE --ack OPERATOR_OWNED_INCIDENT_JOURNAL
python3 deploy/resilience/incident.py ack \
  --journal "$JOURNAL" $BASE --ack OPERATOR_OWNED_INCIDENT_JOURNAL
python3 deploy/resilience/incident.py mitigate \
  --journal "$JOURNAL" $BASE --ack OPERATOR_OWNED_INCIDENT_JOURNAL
python3 deploy/resilience/incident.py resolve \
  --journal "$JOURNAL" $BASE --ack OPERATOR_OWNED_INCIDENT_JOURNAL
python3 deploy/resilience/incident.py review \
  --journal "$JOURNAL" $BASE --ack OPERATOR_OWNED_INCIDENT_JOURNAL \
  --review-code FOLLOWUP_REQUIRED
```

严格的状态转移为：

```text
INCIDENT_OPENED → INCIDENT_ACKNOWLEDGED → INCIDENT_MITIGATED
→ INCIDENT_RESOLVED → INCIDENT_REVIEWED
```

非法跳级、重复确认、事件 ID/工单号/服务改变、伪造或篡改哈希链均被拒绝。只存标准操作代码、事件 ID、工单编号、对应服务和 SHA-256 前后记录。避免任意自由文本、凭据、用户信息或告警原始负载进入日志。

- `INCIDENT_RESOLVED` **只是运维记录状态**，不代表 Alertmanager 中的告警已消失；必须复测健康、用户旅程和告警实际状态。
- 事件 Journal 每次执行前先验证原有哈希链，首次创建为 `0600` 且拒绝覆盖已有文件；只支持单操作员写入，不适合多主机并发记账。运营团队必须另外保存尾部 SHA-256、备份并限制文件访问。
- `--ticket` 是人为输入的变更单引用，不代表工具已经向审批系统核实身份。审批、双人复核和长期不可变保存应由外部运维平台实施。

## 3. 故障复盘模板

每次产生 `INCIDENT_REVIEWED` 前应在受控的外部事件工单中保存：

1. 事件开始/发现/确认/缓解/恢复/复盘时间，分离机器指标与用户报告。
2. 影响范围：用户可见错误、上传/下载失败量、消息延迟及 AI 降级边界；绝不粘贴生产令牌或用户原始文件。
3. 证据链接：Prometheus 查询、Grafana 面板、Jaeger Trace、对应的哈希日志尾部摘要和批准的变更单。
4. 根因及诱因：代码、配置、容量、外部依赖、消息可靠性；尚不确定时标记 `ROOT_CAUSE_UNKNOWN`。
5. 恢复步骤、观察窗口、RTO/RPO（必须给出实测数据而非估计）及仍需跟进的预防措施和责任人。

复盘分类可取 `ROOT_CAUSE_IDENTIFIED`、`FOLLOWUP_REQUIRED`、`ROOT_CAUSE_UNKNOWN`。长期事件分析应在外部 ITSM 系统完成；本工具只保存受控的事件生命周期，不保存工单正文。

## 4. 验证、CI 和上线边界

```bash
python3 -m unittest discover -s deploy/resilience/tests -v
mvn -B verify
cd cloudchunk-web && npm ci && npm run build
```

M3.8 继承 M3.7 的真实独立 Docker Redis/MinIO/RocketMQ 演练、Alertmanager 合成 Webhook 到达和备份恢复检查，不会将生产告警或生产数据注入 CI。部署后的**生产级 SLO 判定、真实值班送达和用户可见事故处置**仍需人工验收。

后续可在保持审批与安全边界的前提下，增加多维度业务 SLI、外部独立探针、ITSM 工单同步、真正多操作员不可变事件存储与自动生成故障复盘。
