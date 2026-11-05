# M3.6 P1：依赖诊断与可控故障回归

> 本阶段交付的是**只读运维面板和模拟故障回归**。不等于已部署生产混沌工程或完成跨机房故障自愈。默认所有检查都不修改用户文件、数据库、消息队列或系统配置。

## 一、管理员依赖状态面板

管理员进入「管理后台 → 依赖状态」，点击「重新检测」调用：

```http
GET /api/v1/admin/health-summary
Authorization: Bearer <管理员用户令牌>
```

新 API 继承 `/api/v1/admin/**` 的 Spring Security `ADMIN` 限制，不提供未认证状态查询、故障开关或服务重启操作。返回 `Cache-Control: no-store`，仅包含组件 ID、有限状态、检查类型、采样时间和 10 分钟前仍未完成的 Outbox 事件数量。**不会包含数据库地址、令牌、MinIO Bucket、用户/文件标识、对象存储路径或原始异常信息**。

| 组件 | 检查类型 | 状态含义 |
| --- | --- | --- |
| MySQL | JDBC `Connection.isValid(2)` 与 SELECT 只读 Outbox 计数 | `UP` 或 `DOWN`，Outbox 查询异常时计数 `null` |
| Redis | 通过 Spring Redis factory 执行 `PING` | `UP` 或 `DOWN` |
| RocketMQ NameServer | 700ms TCP 连接 | `REACHABLE_ONLY` 或 `UNREACHABLE`，**不证明 Broker 消费或生产成功** |
| MinIO | 700ms TCP 连接 | `REACHABLE_ONLY` 或 `UNREACHABLE`，**不证明对象读写字节完整** |
| AI Chat / Embedding / Qdrant | 配置状态 | `DISABLED` 或 `NOT_PROBED`，**绝不发起计费的模型调用** |

总状态只会是 `PARTIALLY_VERIFIED` 或 `DEGRADED`：即使所有 TCP 检查可连接也不会声称系统全部健康。MySQL/Redis 不可用、Outbox 查询异常或存在超过 10 分钟未完成任务时都会展示风险。该接口**不是负载均衡器就绪检查**，不要据此自动切换流量。

## 二、隔离故障模拟的 CI 回归

`mvn -B verify` 包含以下故障注入回归；全部通过 Mockito、独立内存状态或 **127.0.0.1 随机端口**进行，绝不连接真实云服务：

1. **MinIO 故障**：模拟连续失败，观察 Resilience4j 熔断打开、后续请求快速拒绝，在模拟依赖恢复并进入 HALF_OPEN 后恢复 CLOSED；确认非幂等文件流操作没有盲目重试。
2. **RocketMQ 故障**：注入带虚构私密信息的 Producer 异常，验证 Outbox 被标记为可重试、错误内容分类脱敏；下一次 Broker ACK 后才调用 `markPublished`，保护业务消息不被错误标记成功。
3. **AI Provider 故障**：使用本地假 HTTP Provider 返回 503，确认 Chat/Embedding 返回有限且不泄露上游信息的 `AI_PROVIDER_UNAVAILABLE`；服务恢复为 200 后验证响应/向量格式仍正常。
4. **MySQL/Redis 故障**：模拟 JDBC 断开和 Redis 连接失败；管理员读数返回 `DEGRADED`，Outbox 未知状态必须为 `null` 而不是 0；断开异常不得泄露口令。

单独运行：

```bash
mvn -B -pl cloudchunk-api,cloudchunk-core,cloudchunk-storage,cloudchunk-mq -am test
```

CI 保持现有 `M3.6 release and stability gates` 和 `Knowledge workspace checks`，且生产手动只读门禁不会自动注入故障。上述单元回归**不能代替**真实 Docker Compose 下中断 MinIO、Redis/RocketMQ Broker、数据库、网络分区的端到端恢复演练，也没有做自动数据回滚。

## 三、真实故障演练 SOP（仅用于隔离空环境）

生产前安排经过审批的**独立演练环境**，必须与生产账户、对象桶、数据库实例、Broker、网络、凭据和 Docker Volume 完全隔离。需有一次性测试用户、记录起始数据库行数/存储对象数量、已验证的隔离备份以及明确的停止条件。测试前检查运行环境的容器及网络，不要对 `cloudchunk` 默认生产项目执行 `docker compose stop`。

按顺序分别演练 Redis 断开、MinIO 不可达、RocketMQ NameServer/Broker 中断及 AI Provider 503/超时；每次只注入**单个**故障，验证：

- 认证/请求按预期失败且不会泄露凭据，Redis 恢复后可以重新登录；
- MinIO 不可用时不将半写入文件误报为可用，恢复后数据字节校验正确；
- Broker 中断时未确认的 Outbox 留在数据库中，恢复后幂等消费，确认无重复副作用或不可恢复积压；
- AI Provider 不可用时应显示明确降级，不自动发起未经授权的额外调用，恢复后在明确授权下才能再次推理；
- 每种故障都采集恢复耗时、重试次数、HTTP 指标、数据一致性证据及操作审计。

**切勿**通过本项目运行生产断电、Docker Volume 删除、数据库回滚或强行清理消息。出现数据不一致时停止演练，保留数据证据，走 M3.5 的人工恢复审批流程。

## 四、待完成

本阶段包含后台只读依赖状态页面和模拟故障测试；尚未交付生产级混沌控制面、跨机房自愈、RocketMQ/MinIO/Redis 专用协议级 Exporter、AI Provider 的独立健康探针、Alertmanager 真通知、自动重路由与 RPO/RTO 实测。故障注入默认不能出现在生产 Web 管理后台，所有高风险操作需经运维独立审批。
