# M2.5：可选 Qdrant 向量镜像

本阶段将 Qdrant 作为 MySQL 已授权 Embedding 的可丢弃候选检索镜像，而非取代 MySQL 的用户权限、状态、索引版本或正文证据管理。关闭时原 MySQL 混合检索照常运行。

## 配置与部署

执行 Flyway V8 迁移后，运行：

    docker compose -f deploy/docker-compose.yml --profile vector up -d qdrant

API 与 Worker 两个运行角色采用相同配置：

- AI_QDRANT_ENABLED=true（默认 false）
- AI_QDRANT_URL=http://qdrant:6333（Compose 内部地址）
- AI_QDRANT_ALLOW_HTTP=true（仅可信私有 Docker 网络，可保持 false 并使用 HTTPS）
- AI_QDRANT_API_KEY=（可选；私网以外必须启用服务端认证）
- AI_QDRANT_COLLECTION_PREFIX=cloudchunk_ai
- AI_QDRANT_SYNC_INTERVAL_MS=15000
- AI_QDRANT_PRUNE_INTERVAL_MS=90000

运行完整容器栈时同时开启 app 与 vector profile。Compose 只将端口 6333 绑定到宿主机 127.0.0.1；生产须配置 TLS、API Key、网络访问限制与密钥轮换。不要将无认证的 Qdrant 直接暴露到公网。

## 数据与权限边界

1. 上传成功不会触发 Embedding。仅用户已主动授权且知识索引及所有向量 READY 的文档可进入镜像队列。
2. Worker 将数值向量及最小元数据（user_id、file_id、generation、chunk_index）写入 Qdrant，不写正文或对象存储密钥；物理文件即使被共享，Qdrant 也为每位授权用户使用独立的 UUID 点。
3. 使用 knowledge_embedding_job.qdrant_synced_at 作为回填标记；出现故障时跳过并重试。每 24 小时重新检查并同步，以便丢失后逐步重建。
4. 只有当前用户所有 READY 文档都同步完成，才尝试 Qdrant 检索。Qdrant 通过 user_id 筛候选，Java 还须用 MySQL 检查当前用户文件节点、向量记录、索引版本及 READY 状态，并重新加载原文证据；最终与关键词候选采用 RRF 融合。
5. 若仍在同步、Qdrant 无法使用或结果没有有效点，会保留已有的 MySQL 混合检索。对应模式为 QDRANT_WARMING、QDRANT_FALLBACK；正常镜像模式为 QDRANT_HYBRID_RRF_LIMITED。

## 重要限制

用户撤销、回收、物理删除后无法通过 Qdrant 候选绕过 MySQL 实时授权。但 Qdrant 本身的旧派生点是最终一致删除：Worker 每 90 秒扫描一页，删除失去有效授权、索引版本、向量记录的点。不能将其宣称为立即物理删除。若需即时擦除保证，应继续关闭可选镜像并使用专门的删除策略。

每次同步至多一份文档（1–64 个片段），每次 Qdrant 查询最多 100 个候选；本版本仍无法保证大语料召回全部相关文档。改变 Embedding 模型后会使用新 collection；旧模型 collection 需要管理员单独清理。生产使用前还需要真实 Qdrant 端到端集成测试、故障演练、压力基准和清理 SLA 验收。

官方参考：https://api.qdrant.tech/api-reference/search/query-points
