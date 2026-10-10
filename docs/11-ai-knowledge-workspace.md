# AI-Native 第一阶段：资料工作台

本次是 M1 文件知识底座，不是完整的 RAG 或 Agent 产品。保留 Java 21、Spring Boot 3.3.4、MySQL、RocketMQ、MinIO 和现有文件管理，不增加模型账号、云端数据传输或向量数据库。

## 现在可以使用

进入「文件列表」页面，选择「资料工作台」。新上传、校验成功的受支持文档自动建立正文索引；存量文档点击「建立索引」。输入文档内的关键词，查看匹配片段与版本，打开证据面板阅读正文。用户仍可切回文件浏览、上传、下载和分享。

支持 TXT、Markdown、文本型 PDF、DOCX，以服务器记录的 MIME 为准。每份输入最多 20 MB，提取文本最多 262144 个 UTF-16 字符。片段最多 1200 字符，优先在换行处切分，保留提取文本的起止偏移，不伪造 PDF 页码。当前是单片段内的字面关键词匹配，不是语义搜索；跨片段的短语可能无法命中。返回最多 20 个证据片段，并明确提示还有更多结果，不能将此当作全量统计。

状态：NOT_INDEXED、PENDING、EXTRACTING、READY、PARTIAL、FAILED、EMPTY、UNSUPPORTED。READY 表示受支持的正文提取流程结束，不保证扫描图像、视觉信息、复杂排版、表格结构和附件均被理解。OCR 和内嵌附件解析被禁用。达到字符上限或跳过内嵌资料会显示 PARTIAL；扫描文件没有提取到正文时显示 EMPTY，不假装已经理解文档。

本阶段不生成 AI 摘要、答案、标签、自动整理操作，不提供外部模型入口，也不执行文档中的指令。下一阶段再加入向量索引、语义检索与带引用的回答。

## 事件与可靠性

`ChecksumTransitionService` 在 `MERGED -> AVAILABLE` 的同一个事务中额外写 `FileAvailable` 到 Outbox，独立于媒体 MIME 分支。`OutboxPublisher` 显式识别新事件类型，topic 为 `cc-file-available`；生产环境关闭自动建 topic 时，需要提前创建此 topic。

新消费者 `CG-knowledge-v1` 在 transcode 适配模块中运行，复用现有 API/Worker 角色隔离。独立的新模块留待模型/检索边界扩大时拆分；本阶段 Core 只包含知识业务与持久化，不依赖 MQ 或解析器。

`ConsumerEventKey` 为新订阅者生成 `(consumer, eventId)` 的 SHA-256 幂等键，保持现有 64 字符 Inbox 列及旧消费者键兼容。所有新订阅者需要显式使用这个工具；旧消费者未自动迁移，也没有改变其历史幂等语义。原始 eventId 仍用于 Trace。

文件级任务另外使用 generation、claim_token 和 30 分钟租约。新 Worker 抢占后，旧 Worker 的提交会被 token 拒绝。片段替换和 READY/PARTIAL 状态在一个事务内提交。自动最多尝试 3 次；一分钟的补偿任务只写持久化 Outbox，不在调度线程中解析。手动失败重试有一分钟冷却，并重置次数。已就绪文件不会因为重复消息而重算。

AI 索引状态不修改文件 AVAILABLE 状态。模型尚未接入，文件下载不会因为解析失败而被禁用。但启用功能后，新增 Outbox 写入属于文件状态提交事务；数据库或 Outbox 写入失败仍然会使该事务回滚并由原有链路重试。

## 权限和生命周期

API 只取认证上下文中的 userId，不接受客户端传入所有者。资料列表、关键词检索和正文读取均关联当前 `user_file.user_id/status` 与 `file_meta.status`。客户端用逻辑 userFileId 访问正文，不能拿共享物理 fileId 绕过授权。

第一阶段直接以实时数据库关联作为知识绑定，不新增可能滞后的物化 ACL。转存、重命名、移动、回收、恢复均立即反映在后续请求里；移动不触发重解析。支持相同物理内容复用，但每个用户只能看到自己活跃节点上的文件名。公开分享链接不会成为此 API 的授权来源，分享内容不进入公开知识搜索。

所有新 API 使用 `Cache-Control: no-store`。前端不把证据写入 localStorage，在退出或切换工作台时销毁视图并中止请求。已经返回并被用户阅读的内容无法追溯收回；“撤销立即生效”仅指撤销提交后开始的新访问。后台每分钟至多清理 100 份已经物理删除或进入 DELETING 的索引，并级联删除片段；回收站期间保留派生数据以供恢复，但禁止读取。

查询是受 5 秒超时约束的 MySQL 字面包含匹配。没有中文语义能力，也没有大型语料性能承诺；大规模部署前需测量查询计划、增加合适全文/向量检索，并继续保留实时权限过滤。

## API

- `GET /api/v1/knowledge/documents?page=1`：分页资料列表，每页 20。
- `POST /api/v1/knowledge/search`：`{"query":"断点续传"}`，2–100 字符，字面匹配，不将 `%`、`_` 当通配符。
- `GET /api/v1/knowledge/documents/{userFileId}/blocks?generation=1&page=1`：读取精确处理版本的片段；旧版本不静默切换到新版本。
- `POST /api/v1/knowledge/documents/{userFileId}/index`：请求存量文档索引或重试失败任务，不覆盖原文件。

## 升级与验证

先部署包含 V5 迁移和新事件反序列化支持的版本，再同时启用新 API 与 Worker。不要在新 API 开始发布事件后回滚到不认识 FileAvailable 的旧 Worker，否则 Outbox 会持续重试。V5 只增加派生数据表，不修改已有迁移。回滚前先停止新任务并排空新类型事件；不要直接删除正在使用的表。

Tika 统一由 2.9.2 升到 3.3.2，core 和 parsers 使用同一个版本属性。参考 Apache 官方下载页 https://tika.apache.org/download.html 和安全公告 https://tika.apache.org/security.html 。这不是全依赖安全审计；生产部署仍需要依赖扫描及回归测试。解析器只对允许格式显式派发，并限制读取字节、提取字符和消费者并发。不把这些限制称为沙箱：不可信的复杂文档仍建议放在有内存/CPU/磁盘配额、无非必要网络访问的隔离 Worker 中处理；当前没有强制终止失控解析器的硬超时。

验证命令：

```sh
mvn verify
cd cloudchunk-web
npm ci
npm run build
```

新增测试覆盖 Unicode 分块与偏移、事件独立于媒体 tag、多订阅者幂等键、文本截断、真实 MySQL 上的跨用户隔离/回收恢复/文件状态/旧 Worker fencing/原子提交/重试耗尽。Docker 不可用时 Testcontainers 用例明确跳过，不应把跳过当成通过。`.github/workflows/knowledge-workspace.yml` 定义完整后端与前端检查，实际结果以 GitHub Actions 为准。

## 尚未完成

文档格式真实样例的完整回归、浏览器端到端验证、容量和解析器资源压测，以及生产环境部署验收需要继续执行。当前不包括语义向量、模型问答、页级引用、租户工作空间、生成产物和文件操作 Agent。

## 后续修复（2026-10-10）

- API/Worker 装配集成测试使用 `cloudchunk.storage.type=local`，避免装配验证意外依赖未启动的 MinIO；MinIO 传输能力应由专门的对象存储集成测试验证。
- 文件进入回收站期间完成索引，派生内容可保留以供恢复；新读取仍实时要求活跃 `user_file`。彻底删除最后一份逻辑引用后会清理孤儿索引。
- 检索结果以关键词附近的上下文作为摘要，点击结果跳到对应的证据片段页，并高亮当前片段，不伪造原文页码。
