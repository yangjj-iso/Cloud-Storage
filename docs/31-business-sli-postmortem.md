# M3.9 业务级 SLI 与基于证据的故障复盘

> **状态：工程指标、告警规则与复盘草稿生成器。** 本阶段没有真实生产用户量、真实值班送达或经业务签署的 SLO 结论；CI 使用合成数据和隔离 Docker。与 [M3.8](./30-operations-slo-incident.md) 的 HTTP 观察 SLO 并行，**不得把这些局部指标称作完整业务 SLA**。

## 1. 业务指标口径与缺失条件

| 实际指标（Prometheus） | 来源 | 能证明什么 | 不能证明什么 |
| --- | --- | --- | --- |
| `cloudchunk_business_operation_total{operation="upload_merge",result="accepted"}` | API `POST /api/v1/upload/merge/{fileId}` 成功返回 | 显式合并操作被服务器受理 | 自动合并、最终校验完成、文件 AVAILABLE |
| `cloudchunk_business_operation_total{operation="download_url",result="accepted"}` | API `GET /api/v1/file/{fileId}/url` | 已签发被授权的下载链接 | 浏览器是否使用过签名 URL，是否完整下载数据 |
| `cloudchunk_business_operation_total{operation="download_stream",result="completed/failed"}` | API `GET /api/v1/file/{fileId}/download` | 服务端读取、写出并 flush 结束，或流执行失败 | 客户端持久化成功、下载 MD5 等于源对象；304 缓存命中不计数 |
| `cloudchunk_business_async_available_recent` / `_broken_recent` | API 每 60 秒只读采样 MySQL `file_meta` | 最近 24 小时有状态更新的 AVAILABLE/BROKEN 文件**当前行数** | 严格的 24h 状态转换次数或消息逐次 ACK；文件可能更新、重复、被删除 |
| `cloudchunk_business_async_outbox_stale` | MySQL `event_outbox` | 超过 10 分钟仍未完成的待投递事件数量 | RocketMQ Broker 消费及用户业务幂等全部正确 |
| `cloudchunk_business_async_sample_valid` | 采样器 | 1 = 本次数据库读取完整，0 = 异常/未初始化 | 未采集时应当自动推断故障为零 |
| `cloudchunk_business_ai_retrieval_total{kind="fallback/semantic/not_ready/other"}` | API 有明确用户授权的混合检索/自动问答 | 成功返回的检索结果属于哪类模式 | AI 模型质量、所有第三方 Provider 调用次数、用户回答正确性 |

所有 Prometheus 标签只有固定枚举 `operation`、`result`、`kind`，不包含用户 ID、文件名、文件路径、提示词、Token、鉴权资料。上传错误统计包含服务端 5xx/异常，但主动取消、未授权、无效参数用 `client_error` 标签区分；不要将用户验证失败计入存储服务不可用。

下载流的失败类别可能包含客户端主动断开连接，因此告警使用最小样本限制，并需要结合反向代理连接日志、Trace 和用户反馈复核。对于 MinIO presigned 直传/直下，应通过外部安全探针进行 ETag/字节哈希校验；API 无法观察浏览器是否真实完成对象读取。

**异步采样边界：** API 实例通过已启用的 Spring 定时任务对真实数据库执行轻量 SELECT，初始值为 `-1` 且 `sample_valid=0`；任何查询失败会清除上次数据并标为无效。显示异步质量时必须要求 `sample_valid == 1`。Worker 无 HTTP 指标端点，这些只读 SQL 快照并不替代真正的 Worker 消息处理完成事件。

## 2. Prometheus / Grafana

`deploy/observability/rules/business-sli.yml` 包含：

- 10 分钟显式上传合并服务器错误比例（至少 20 次非客户端错误操作）。
- 10 分钟服务器端下载流失败比例（至少 20 次实际流终态）。
- AI 检索降级比例（10 分钟至少 20 次被计量的检索结果；门限 30%，可依据使用场景调整）。
- 超过 10 分钟的 Outbox 仍未完成任务（`sample_valid=1`），以及指标采样器本身失败。

Grafana「CloudChunk 运行总览」新增上传合并、下载流、AI 降级比例、24 小时更新的文件终态和 Outbox 积压图。告警仍走 M3.7 的受保护 Alertmanager；**真实值班收件人验证不在 GitHub CI 执行**，需人工配置并检查通知实际送达。所有阈值为初始经验值，不代表已测量或经业务批准。

## 3. 从已审核事件日志生成复盘草稿

M3.8 的受限状态机 `deploy/resilience/incident.py` 要求 `OPENED → ACKNOWLEDGED → MITIGATED → RESOLVED → REVIEWED`；事件 JSONL 带 SHA-256 链，必须存放在仓库外的权限受限目录。

完成独立审批后可以将**已 REVIEWED 的完整事件**转成 Markdown 草稿：

```bash
umask 077
python3 deploy/resilience/postmortem.py \
  --journal /secure/incidents/INC-20261011A.jsonl \
  --output /secure/reviews/INC-20261011A-review.md \
  --ack REVIEWED_INCIDENT_DRAFT_ONLY
```

脚本会重新验证哈希链、事件 ID/服务/工单引用、五个顺序完整的状态及复盘分类，然后以 `0600` 权限 **独占创建新的** Markdown 文件。重复输出路径、被篡改的日志、未完成的复盘、在仓库内部的目标路径都会失败。

报告仅列出可靠的操作事件日志时间和来源 SHA-256；**不会自动推断**用户可见故障的真正起止、影响范围、实际 RTO/RPO、数据完整性、责任人、根因或是否需要赔偿。所有这些项以 `NOT_VERIFIED` / `HUMAN_REVIEW_REQUIRED` 等显式占位符交给复盘负责人进一步验证、签批和更新。工具不导出原始工单、告警负载或敏感文件正文。

## 4. 交付验收与后续工作

```bash
python3 -m unittest discover -s deploy/resilience/tests -v
python3 -m unittest discover -s deploy/release/tests -v
mvn -B verify
cd cloudchunk-web && npm ci && npm run build
```

CI 还包含 Prometheus `promtool`、生产配置门禁、隔离 Docker 备份/恢复、临时 Redis/MinIO/RocketMQ 中断恢复与 Alertmanager→私有 Webhook 合成通知。发布后在生产环境开启业务采集，至少等待一个完整观察窗口，核对 Prometheus 样本/窗口、采样有效性以及人工文件上传/下载字节校验后，才能提出真实业务 SLO 的签批建议。

**仍未实现：**浏览器/对象存储端完整直传与直下成功率、按不可变事件计数的异步任务完成率、RabbitMQ/RocketMQ Broker 消费端到端成功率、AI 答案真实质量评测、外部 ITSM 双向工单同步、真正多值班员不可篡改审计账本。后续计划先在隔离生产等价环境为这些缺口建立端到端探针，再讨论自动化处置。
