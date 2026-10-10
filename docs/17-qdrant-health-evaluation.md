# M2.6：Qdrant 真实集成测试、合成检索基准和用户级索引健康

## 新的集成验证

- cloudchunk-boot/src/test/java/com/cloudchunk/knowledge/QdrantRealContainerIT.java
- 使用 Testcontainers 启动与 Compose 相同的 qdrant/qdrant:v1.15.0。
- 完整验证真实 REST collection 创建、8 维 cosine 配置、维度不匹配拒绝、批量写入、按 user_id 过滤、points/scroll，以及按 UUID 删除后不可再检索。
- 放入三个互相正交的合成向量，每个查询的目标文档必须排到 Top-1，并验证合成 Recall@1 和 MRR 都为 1.0。此测试只验证协议及排名机械性质，不代表真实中文文档上的召回率。
- 延续现有 MySQL Testcontainers 权限回归，验证每个用户只能读取自己的派生索引状态。索引同步、撤销、Qdrant 不可用等状态不会绕过 user_file 当前 ACL。

CI 执行 mvn verify，将自动启动 MySQL/Redis/Qdrant 容器；开发环境没有 Docker 时 Testcontainers 用例明确跳过，跳过不是通过。前端通过 npm ci 和 npm run build。

## 索引健康接口

GET /api/v1/knowledge/semantic/health （登录必需，Cache-Control: no-store）返回当前用户可访问、已完成 Embedding 的文档情况：

- state：EMBEDDING_DISABLED / MYSQL_ONLY / NO_INDEX / SYNCING / READY / DEGRADED
- readyDocuments：当前用户的 READY 文档数
- mirroredDocuments：最近 24 小时同步过的文档数
- pendingDocuments：待同步数
- qdrantReachable：当前 collection 结构和维度校验是否通过

其中 READY 还要求在 Qdrant 端确认 collection 可用；未完成同步时显示 SYNCING。这个 endpoint 不返回其他用户的文件总数、原文、存储路径或供应商 API Key。它是用户侧“当前检索路径状态”，不等于全局服务 SLO。

资料工作台在搜索框下提供索引健康条，显示“Qdrant 已就绪 / 同步中 / 不可用回退 / MySQL 索引”等状态和当前账户完成数，支持手动刷新。它不会主动发起额外 Embedding / Chat 请求，也不消耗模型调用额度。

## 运维与限制

真实 Qdrant 集成测试仅覆盖固定小尺寸合成向量，没有验证真实中文 Embedding 模型质量、动态增加新文档的大规模后端时延、长期租约竞争、跨实例镜像一致性或生产 TLS/API Key。下一步应该维护脱敏标注 query → 相关文档集，并在部署环境测 Recall@5、MRR@10、P95/P99、Qdrant 断网恢复时间和镜像滞后分布。

真正生产时 Qdrant 旧向量的物理清理仍是最终一致；权限立即撤销依赖 MySQL 重新鉴权。若必须满足即时物理删除 SLA，不应直接开启该可选功能。
