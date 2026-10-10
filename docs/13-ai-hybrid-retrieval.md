# M2.2：用户主动开启的混合检索（小语料版）

这是 **真实 Embedding + 关键词匹配** 的小规模试验版，不是大规模 ANN 检索服务。Flyway V6 添加派生向量及按用户授权的持久化索引任务，不影响原有对象存储与下载。

## 管理员配置

必须在 API 与 Worker 两种运行角色中配置相同的模型参数：

| 环境变量 | 配置 |
| --- | --- |
| AI_EMBEDDING_ENABLED | true，默认为 false |
| AI_EMBEDDING_ENDPOINT | HTTPS 的 OpenAI-compatible /v1/embeddings 地址 |
| AI_EMBEDDING_MODEL | 模型名称 |
| AI_EMBEDDING_DIMENSIONS | 与模型返回一致的维度，8–2048，默认 384 |
| AI_EMBEDDING_API_KEY | 可选；仅放服务端 |
| AI_EMBEDDING_ALLOW_HTTP | 默认 false；只为可信局域网服务开启 |
| AI_EMBEDDING_JOB_INTERVAL_MS | 每个 Worker 处理一个片段的周期，默认 10000ms |

Embedding 的请求与响应结构参照 OpenAI Embeddings API：https://developers.openai.com/api/reference/resources/embeddings/methods/create 。不是所有兼容提供方都接受 dimensions 参数，需进行适配测试。

## 授权及用户体验

文件上传和 M1 正文解析不会自动触发 Embedding。用户必须在文档列表主动确认每份文档的内容会发送至部署方选择的模型服务（可能在外部），才能排队建立语义索引。最多支持正文已就绪且包含 **1–64 个片段**的文件；超过上限会拒绝，而不是悄悄截断索引。

Worker 每次只发送一个最多 1200 字符的正文片段；索引有持久化进度、60 秒租约、token fencing 与最多三次连续失败重试。用户可点击撤销，后续不能通过该用户的授权检索向量。已发往模型的请求不能撤回，请管理员核实提供方的数据保留、训练政策与成本限制。

用户切换到“语义 + 关键词”模式时，还必须对**此次查询文本**单独勾选传输授权，提交后 UI 自动取消勾选。普通关键词模式不经过模型，始终可用。

用户可以将排名靠前的四条候选证据加入 M2.1 问答面板，**但不能跳过来源核对和问答授权**。这不是无需审批的 Agent。

## 检索实现与准确性边界

- knowledge_vector：按物理文件、解析 generation、片段编号、模型 fingerprint 保存 JSON 向量，外键约束确保原片段被删除或重解析时向量失效。
- knowledge_embedding_job：按 userId 独立保存授意与进度，即使多个用户引用同一物理文件，也不能让未授权用户自动获得语义检索结果。
- 每个向量候选首先在 SQL JOIN 中检查活跃 user_file、AVAILABLE 的物理文件、当前解析 generation 与此用户的 READY 索引任务；返回前再次调用原 KnowledgeService.source 复核实时权限。
- 余弦相似度（最低 0.20）与关键词结果经 RRF 倒数排名融合；限制最多扫描 2000 个可见候选、最多返回 20 个片段。为避免误解，模式返回 HYBRID_RRF_LIMITED；**它不等于全盘无遗漏的搜索**。
- 没有当前账号已就绪索引时显示 SEMANTIC_NOT_INDEXED，且不会发送额外 Embedding 请求。提供方临时不可用时返回明确标记的 KEYWORD_FALLBACK，用户可继续使用关键词模式。
- 没有追加 Qdrant、Milvus 或向量专用数据库。本版 MySQL JSON + JVM 线性扫描只适用于小范围 PoC；扩容前应替换为真正的向量索引，评估中文语料召回率、延迟和成本。

## API

- GET /api/v1/knowledge/semantic/capabilities
- POST /api/v1/knowledge/documents/{userFileId}/semantic-index（JSON：{"consent":true}）
- GET /api/v1/knowledge/documents/{userFileId}/semantic-index
- DELETE /api/v1/knowledge/documents/{userFileId}/semantic-index
- POST /api/v1/knowledge/semantic/search（JSON：{"query":"问题","consent":true}）

模型出错和索引失败不应阻断上传、下载、分享及原文件状态。请求受现有用户认证、实时业务权限和速率限制保护；凭证不发送到浏览器。

## 生产前必需的后续工作

需要添加真实模型端到端兼容测试、容量与延迟基准、完整中文资料评测集、每用户/租户按日 Token 与费用的硬额度、模型密钥轮换与出口控制、撤销授权并发压力测试。所有被传给外部模型的片段都应有明确的用户告知与部署者治理政策。

验证：运行 mvn verify；前端执行 npm ci、npm run build。Docker 不可用时 Testcontainers 测试会跳过，不能将跳过当作通过。
