# M3.7 生产韧性验收与告警闭环

> **范围说明：**本阶段交付「可重复的隔离 Docker 故障注入」「可运行的告警路由基础设施」「不可覆盖的演练审计证据」。运行在 GitHub Actions 的**一次性本机 Docker 守护进程**内；没有停止生产服务、提交真实故障工单或验证真实 PagerDuty/企业微信/邮件收件人。不是生产自动自愈，也没有更改数据恢复权限。

## 1. 不改变生产数据的真实故障演练

真实 Docker 场景基于 `deploy/resilience/compose.yml`，只包含独立 Redis、MinIO、RocketMQ NameServer 三个服务，镜像没有任何生产密钥；隔离演练和开发环境使用固定标签的 S3 兼容镜像 `docker.io/pgsty/silo:RELEASE.2026-09-16T00-00-00Z`，仅作为开发/演练依赖。原 Docker Hub `minio/minio` 已无法拉取；这**不是生产对象存储迁移批准**。生产仍须审查镜像、验证原对象数据兼容性、锁定 digest，并经独立备份恢复演练；不挂载业务数据、不发布主机端口、不连接已有网络或卷。项目名由随机值产生并强制匹配 `ccdrill-<12 位十六进制>`，只对刚创建的项目停止、重启、清理服务。

`deploy/resilience/chaos_drill.py` 的执行顺序如下：

1. 拒绝远程 Docker Context / `DOCKER_HOST` / `DOCKER_CONTEXT`、生产上下文标记及不安全 Compose 拓扑。
2. 建立随机一次性 Compose 项目，等待 Redis PING、MinIO HTTP liveness、RocketMQ NameServer TCP 端口探测通过。
3. **一次只停止一个依赖**。观测探针变为失败后立即启动，再等待同一个探针恢复，通过后才进入下一个依赖。
4. 把每个服务的中断确认、恢复确认、耗时写入带 SHA-256 前向链的私有 JSONL 日志。
5. 无论通过或失败都尝试删除**本次随机 Compose 项目**。任何探针超时、配置漂移、审计不完整或清理失败均阻断 CI。

在专用、已确认与生产隔离的 Docker 主机上可手动执行：

```bash
umask 077
python3 deploy/resilience/chaos_drill.py \
  --ack ISOLATED_DISPOSABLE_DOCKER_ONLY \
  --ticket TEST-ISOLATED-2026 \
  --journal /secure/drills/redis-minio-rmq-2026.jsonl
```

日志位置必须在仓库外且**尚不存在**，父目录事先建立并仅授权演练人员访问，日志权限限定 0600。不要使用已有业务 Docker 主机，因为本地 Docker Context 校验并不能证明该主机没有生产容器。脚本只验证临时实例的基本可用性；RocketMQ 测的是 NameServer TCP，**不等于 Broker 消息消费和幂等恢复已验证**，真实文件字节与 Redis 会话也不在此测试范围内。

## 2. Alertmanager 告警通知配置

`deploy/docker-compose.yml` 的 `observability` profile 新增 `alertmanager` 服务，Prometheus 在配置中向内部 `alertmanager:9093` 转发告警。默认开发配置 `deploy/observability/alertmanager.dev.yml` 使用 `unconfigured` 空接收器，不会通知任何人，也不能当做已完成闭环。

生产环境必须：

1. 将 `deploy/observability/alertmanager.prod.template.yml` 复制到**仓库外**的私有路径；替换示例 `.invalid` 终点为实际批准的 HTTPS 告警接收地址，并由负责人员确认 escalation、重复通知和恢复通知策略。
2. 限制权限为 0600，设置 `ALERTMANAGER_CONFIG_FILE=/secure/cloudchunk/alertmanager.yml`，镜像 `CC_ALERTMANAGER_IMAGE` 使用不可变 digest。生产 Compose 不发布 Alertmanager 的 9093 端口。
3. 先用对应 Alertmanager 版本的 `amtool check-config` 验证真实配置文件，再使用 M3.5/M3.6 的 `config_gate.py` 核对解析后的生产 Compose、文件权限和已显式配置的接收器。**门禁不具备互联网送达证明**，上线人员仍需人工确认通知端到端到达。
4. 在受控演练时只向已批准的**测试接收通道**发送有唯一标记的合成告警，并在目的系统收件/工单记录中核对时间、去重和恢复通知；不能从 CI 向真实生产值班接收方发送测试风暴。

最小验证入口：

```bash
# 示例配置语法（不是线上送达证明）
docker run --rm --entrypoint /bin/amtool \
  -v "$PWD/deploy/observability:/config:ro" \
  prom/alertmanager:v0.27.0 \
  check-config /config/alertmanager.dev.yml /config/alertmanager.prod.template.yml
```

GitHub Actions 另启动**完全独立**、未发布端口的 Alertmanager 和 Python Webhook 接收容器。发送 `CloudChunkSyntheticDelivery` 合成告警，等待私有 Webhook 收到与随机演练 ID 一致的 firing 状态，然后清理网络和容器。接收器**不会保存原始 Webhook payload**、请求头、Token 或真实业务告警详情；此测试仅验证内部路由，并不确认外部收件人。

## 3. 故障事件审计与校验

`deploy/resilience/audit.py` 实现一次性审计文件创建（不可覆盖已存在文件）、私有权限校验、每条事件的编号/时间/前一条哈希/当前 SHA-256 及完整链条验证。只允许标准事件类型和受约束的 `run_id`、`service`、`status`、`reason_code`、`elapsed_ms`、`ticket`；**禁止写入自由文本、主机地址、堆栈、密钥、文件名或用户数据**。

输出日志必须保存到只追加的外部安全日志平台，并独立保存最终 `last_sha256`；SHA-256 链能够发现文件意外更改，但**不是数字签名**，拥有日志写权限的人依旧可能重写整条链，不能单独作为监管级不可抵赖凭证。支持在 Python 程序中调用 `audit.verify(path)` 验证事件连续性。当前写入器限定单进程，不适合作为多实例并发审计数据库。

## 4. 阻断条件与演练验收矩阵

| 验收项目 | 通过条件 | 不能推断出的能力 |
| --- | --- | --- |
| Redis 停机恢复 | 临时 Redis PING 在停止后失败、重启后恢复 | 生产 Session 无损、跨机房切流 |
| MinIO 停机恢复 | 临时 MinIO liveness 失败后恢复 | 对象字节一致性、数据生命周期完整 |
| RocketMQ NameServer 恢复 | 临时 NameServer TCP 失败后恢复 | Broker 投递/重放与业务幂等 |
| Alertmanager | 合成 firing 告警到达私有 Webhook | 生产收件、值班人员确认 |
| 审计 | 每个动作的独立哈希链能校验、篡改能被检出 | 数字签名/长期归档系统 |
| 发布门禁 | CI Python 单测、后端/前端、Docker 恢复和故障演练通过 | 生产故障自愈、RTO/RPO 已达标 |

当 CI 失败时**不得绕过红灯强行合并**。生产上线还需独立值班审批、真实收件通知演练、MySQL/MinIO 配对备份的端到端字节校验、RocketMQ Broker 和 Worker 的幂等回放、真实 RPO/RTO 记录。暂不实现生产自动故障注入、自动重启、数据回滚或公网管理入口。

## 5. CI 执行边界

```bash
python3 -m unittest discover -s deploy/resilience/tests -v
# 以下必须在隔离 Linux Docker 机器上，并且仅可使用随机一次性项目：
python3 -m unittest discover -s deploy/release/docker_tests -p 'test_dependency_chaos_docker.py' -v
python3 -m unittest discover -s deploy/release/docker_tests -p 'test_alert_delivery_docker.py' -v
```

原有的 `M3.5` 备份/恢复、`M3.6` 发布后只读检查与仅应用镜像回滚保护仍然保留。**没有通过的真实 CI 结果，不应当被写成已交付的演练成功结论。**


M3.8 起可将演练输出与单次事件状态机审计关联；详见 [M3.8 运维闭环](./30-operations-slo-incident.md)。
