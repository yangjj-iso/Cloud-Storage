# 架构加固说明

本轮优化仍保持“模块化单体”部署形态，但把同步 API 与异步 Worker 作为两种独立运行角色，并为跨模块通信、数据库演进和前端状态建立明确边界。

## 依赖方向

```text
api -> core -> contract
       |  \-> storage（存储策略抽象与实现）
       \----> infra（MyBatis/Redis 等共享基础设施）
infra -> contract     （Outbox、Inbox、对象删除队列实现）
mq -> core + infra + contract
transcode -> core + contract
boot -> api + mq + transcode
```

- `cloudchunk-contract` 只保存集成事件和端口，不依赖业务或适配器。
- `cloudchunk-core` 不再依赖 `cloudchunk-mq`，业务事务只写 `IntegrationEventPublisher`。
- Controller 只依赖应用服务与公开 DTO；对象存储定位、所有者、引用计数以及原始转码结果/异常不进入普通用户 API。
- ArchUnit 在 CI 中阻止 Controller 绕过应用层，以及 Core 重新依赖 MQ 适配器。
- `BackendModuleBoundaryTest` 从 boot 模块加载完整运行时类路径，同时约束 contract/infra/storage 的稳定基础边界，以及 API、MQ、转码适配器之间不相互反向依赖。
- API 写操作通过脱敏审计过滤器写入 `op_log`；认证请求和高吞吐分片请求明确排除。

## 可靠异步链路

合并成功、校验状态和后续事件在同一 MySQL 事务内提交到 `event_outbox`。Worker 领取记录后同步投递 RocketMQ，只有收到 `SEND_OK` 才标记已发布；失败进入带退避的重试状态，进程中断后的 `publishing` 记录可被其他实例回收。

Checksum 与 Transcode 消费者先领取 `event_inbox`。`event_id` 是消息幂等键，转码记录还以 `(event_id, task_type)` 建唯一约束。业务状态使用比较并交换，例如只有 `MERGED -> AVAILABLE/BROKEN` 成功的消费者才能产生下游事件。

物理对象删除由 `object_gc_task` 驱动。业务事务先把文件标成 `DELETING` 并写删除任务；Worker 删除存储对象成功后再标记 `DELETED`，失败可重试，避免数据库已删而对象遗留。

## 配额与去重安全

上传初始化使用 `reserved_bytes` / `reserved_file_count` 原子预留容量；合并事务把预留转成已用，取消或过期会话释放预留。并发上传不能通过“先检查、后累加”的时间窗口超配额。

默认关闭跨用户仅凭 MD5 秒传和分片复用。只有用户已拥有该文件/分片来源时才复用；如显式开启 `CROSS_USER_DEDUP_ENABLED`，应在生产环境补充所有权或 possession challenge。取消会话先锁定会话行，再以“成功删除参与引用”为释放预留的前提，避免重试或清理竞态误减其他上传的聚合配额。

## 多节点运行

API 与 Worker 都可以横向扩容到多实例，各类共享状态的处理方式如下：

| 关注点 | 多节点下的处理 |
| --- | --- |
| 登录态 | Token 在 Redis，API 进程本身无会话状态 |
| 上传进度 | Redis Hash + Lua 原子更新；进度重建也走 Lua，做并集并重新计数，不会覆盖其他节点并发写入的分片位 |
| WebSocket 推送 | 经 Redis channel `cc:ws:upload-progress` 扇出；连接与分片确认请求可以落在不同节点 |
| Caffeine L1 缓存 | 写入方发布 `cc:cache:invalidate:file_meta`，各节点清除本地副本；处在事务中时通知推迟到事务结束之后 |
| 限流 | Redis 令牌桶，配额在集群内共享而不是每节点一份 |
| Outbox 投递 | `claim(id, workerId)` 抢占 + 陈旧 `publishing` 记录回收，多 Worker 不会重复投递 |
| 过期会话清理 | 以条件 UPDATE 抢占会话，只有状态迁移成功的那个实例释放配额，避免重复释放导致配额少计 |
| 合并 | Redisson 锁 + 幂等状态机；锁内重新读取会话状态，并先锁会话行再读参与者 |
| 转码补偿 | `transcode_status` 上的 CAS（`NONE → QUEUED`）保证只有一个 Worker 产生补偿事件 |
| 校验补偿 | 事件 ID 由「文件 + 时间窗」派生，同一窗口内各 Worker 算出同一个 ID，由 `uk_outbox_event_id` 收敛成一条 |
| 保留策略清理 | `DELETE ... LIMIT` 天然幂等，多个 Worker 同时清理同一批数据时后到的只会得到 0 行 |

两点需要明确：

进度推送是尽力而为的。Redis 发布失败时会退回本地直推，此时多节点部署可能丢推送，但前端有本地进度兜底，上传本身不受影响。

缓存失效通知必须在事务提交之后再发。提交前广播会让其他节点立刻回源，而这时修改尚未提交，它们读到旧值并重新缓存，此后不再有第二次通知，旧值会一直停留到 Caffeine TTL 到期。典型症状是文件已经 `MERGED → AVAILABLE`，但部分节点在几分钟内始终认为它不可下载。

## Redis 负载隔离

Redis 上跑着四类可再生性完全不同的数据。会话数据（登录 Token、登录失败计数、邮箱验证码）丢了就是全员掉线，无法恢复；上传进度可以从 `chunk_record` 与对象存储重建；令牌桶丢了退化成放行、秒级自愈；文件元数据与预签名 URL 缓存丢了只是一次回源。

挤在同一个实例里意味着共享爆炸半径：缓存侧一次驱逐风暴就能把登录态一起带走。`cloudchunk.redis.*` 允许把后三类剥离到独立实例：

```yaml
cloudchunk:
  redis:
    progress: { host: redis-progress, port: 6379 }
    rate-limit: { host: redis-ratelimit, port: 6379 }
    cache: { host: redis-cache, port: 6379 }
```

主实例（`spring.data.redis`）始终承载会话数据，不可配置——它就是会话存储，能剥离的只有可再生的那几类。每一项都是可选的，不配置就继续留在主实例上，行为与拆分前逐字节一致。

**Pub/Sub 不参与这套路由。** channel 不是存储，没有驱逐问题；而发布方与订阅方一旦解析到不同实例，消息会静默消失——正是缓存失效与上传进度这两条链路最怕的失败模式。让它始终留在主连接上可以从根上排除这类不一致。

容器里因此同时存在四个 `RedisService`，未加限定符的注入点由 `@Primary` 落到会话实例。这类装配错误只在应用真正启动时才暴露，切片测试覆盖不到，所以 `RedisWorkloadConfigurationTest` 用 `ApplicationContextRunner` 专门验证了注入不产生歧义、且各限定符落到正确的负载上。

## 运维表的保留策略

`event_outbox`、`event_inbox`、`object_gc_task` 和 `op_log` 的写入速率与业务写入速率同量级，但过了各自的窗口之后不再有价值。不清理的后果不只是占磁盘：投递与消费路径上的索引会跟着变大变慢，而那正是上传链路的关键路径。

`RetentionCleanupTask` 每小时按批清理，只删走完生命周期的行：

| 表 | 删除条件 | 默认窗口 |
| --- | --- | --- |
| `event_outbox` | 已投递成功（`status=2`） | 7 天 |
| `event_inbox` | 已处理完成（`status=1`） | 7 天 |
| `object_gc_task` | 已完成（`status=2`） | 7 天 |
| `op_log` | 按创建时间 | 90 天 |

两个必须说清楚的约束。

**Inbox 的窗口必须大于消息中间件的消息保留时长**（RocketMQ 默认 72 小时）。这些行就是消费幂等键，在 Broker 仍可能重投的时间窗内删掉它，重复投递会被当成新消息重新执行一遍副作用。默认 7 天就是为了留出余量，调小之前先确认 Broker 的实际保留配置。

**仍在流转中的行一行都不能碰**：待投递、重试中、被 Worker 领取中的 Outbox 行，以及处理中和失败待重试的 Inbox 行。写错一个状态码就是静默丢事件，`RetentionPurgeIT` 专门针对每种状态验证了这一点。

清理按 `batch-size`（默认 500）分批、每轮最多 `max-batches-per-run`（默认 20）批，既避免长事务和大范围行锁，也给积压的清理速率设了一个可预期的上限。V4 迁移为这些扫描补上了 `(status, 完成时间)` 索引——原有索引都不以这些列打头，缺了它们清理本身就会全表扫描。

## 数据库与运行角色

- Flyway 是 Schema 的单一来源：`V1` 是空库基线，`V2` 增加 Outbox/Inbox/GC 与转码幂等键，`V3` 增加配额预留并迁移旧的未完成上传占用。Compose 不再自动导入另一份 Schema。
- `application-api.yml` 关闭定时任务和消息消费者。
- `application-worker.yml` 使用非 Web 应用模式，只运行 Outbox、消费、转码、补偿和 GC。
- `docker compose --profile app ...` 启动 API、Worker、Nginx Web 和全部基础设施；不带 profile 时仍可只启动基础设施用于本地开发。

## 前端与验证

Zustand 状态已按 `auth/ui/upload/files/drive/share/admin` 分片，根 store 只负责组合；公开类型与后端 DTO 对齐，不再读取存储桶和对象 key。

验证门禁包括：

```powershell
mvn verify
Set-Location cloudchunk-web
npm run build
docker compose --profile app -f ../deploy/docker-compose.yml config --quiet
```

集成测试在 Docker 可用时运行，否则明确跳过。它们覆盖的都是「Mock 之后就消失」的性质——原子性、隔离级别、锁顺序：

| 用例 | 依赖 | 验证内容 |
| --- | --- | --- |
| `FlywayMigrationIT` | MySQL | 从空库执行全部迁移并检查可靠性表 |
| `ProgressStoreConcurrencyIT` | Redis | 重复确认只计一次、并发确认全部计入、重建与并发写入交错时不丢分片 |
| `QuotaConcurrencyIT` | MySQL | 64 路并发申请恰好放行容量所允许的数量，预留与秒传共用同一上限，重复释放不扣穿，预留缺失时提交显式失败 |
| `MergeCancelLockOrderingIT` | MySQL | 复现「先读参与者再锁会话」导致的陈旧快照，并证明先取会话行锁后两个事务被正确串行化 |

这些用例的判别力经过反向验证：把 `ProgressStore.rebuild` 换回非原子实现后，200 个分片会丢掉 189 个；把配额预留换回「先检查后累加」后，容量 10 的账户会放行 33 个申请。

`MybatisTestSupport` 提供最小的 MyBatis-Plus 装配，直接对着容器里的 MySQL 跑生产的 Mapper 与 XML，不启动 Spring 上下文——这些用例要验证的是 SQL 和事务行为，拉起完整上下文只会把 Redis、RocketMQ、MinIO 一并带进来。注意它复用了生产的 `AuditMetaHandler`：`created_at` / `updated_at` 标了 `@TableField(fill = INSERT)`，MyBatis-Plus 无论字段是否为空都会把它们写进 INSERT 列表，缺了填充器就会插入 NULL 并撞上 NOT NULL 约束。

需要注意 failsafe 的类路径配置。集成测试跑在 `package` 之后，默认会用重打包后的产物；`cloudchunk-boot` 经 `spring-boot-maven-plugin` repackage 之后资源位于 `BOOT-INF/classes/`，`classpath:db/migration` 解析不到任何东西，Flyway 会在「0 个迁移」的情况下报成功，迁移校验形同虚设。因此根 POM 把 failsafe 的 `classesDirectory` 指向 `target/classes`。

## 可观测性边界

`TraceFilter` 只负责请求关联和响应 Trace ID，`AuthenticationContextFilter` 只负责 Bearer 会话及用户上下文，避免追踪与认证生命周期混在一个过滤器中。业务事件在事务写 Outbox 时捕获当前 Trace/Span 上下文；Publisher 创建 PRODUCER span，Checksum/Transcode 创建 CONSUMER span，因此异步处理仍归属于原始 HTTP Trace。

`@Observed` 标记上传初始化、分片、合并和整文件校验，统一生成业务时延指标与子 span。日志同时输出 `traceId` 与 `spanId`，可从 Jaeger 复制 Trace ID 后直接检索日志。
