# M2.4：按用户/UTC 日的模型调用硬上限（不等同于计费）

## 为什么先做调用次数预算

先前所有 AI 入口都只有每秒速率限制，用户依然可能连续发起大量付费 Chat 或 Embedding 请求。新的 MySQL V7 迁移增加 ai_model_daily_usage（user_id、usage_date、model_kind、used_calls），每个模型种类和账号按 **UTC 日期**计数。

在**实际联系外部模型之前**执行原子 INSERT IGNORE + 有界 UPDATE。即使多个 API/Worker 实例同时请求，也不允许超过配置上限。因网络中断、提供方失败、超时而已经登记的调用不退回（可能尚未真正送达）；这是保守的“尝试次数上限”，避免失败重试变成无上限费用。保存的只有次数与日期，不记录用户问题、证据原文、秘钥或模型响应。Worker 每日凌晨调度清理 90 天前的历史计数（每轮最多 1000 条）。

请不要把它称作 Token、人民币或美元成本预算：模型输入/输出 Token 单价不同，且某些网关会重复计费。本版本**只有请求次数上限**，生产还须在模型提供方设置金额与 Token 的额度，后续再用实际 usage 字段建精确账本。

## 环境变量

API 与 Worker **必须采用相同的每日限额配置**，否则不同实例可能在配置上限不一致时按较高一方批准调用：

- AI_CHAT_CALLS_PER_DAY=20：每个用户每天最多尝试 20 次 Chat 推理；设 0 禁止全部 Chat。
- AI_EMBEDDING_CALLS_PER_DAY=100：每个用户每天最多尝试 100 次 Embedding（包括查询与文档索引）；设 0 禁止全部 Embedding。

独立于 AI_CHAT_ENABLED、AI_EMBEDDING_ENABLED；它们仍默认是 false，默认不会向外部发送任何文件。即使 API/Worker 额度已满，原始文件上传、下载、分享和 M1 关键词搜索仍可用。

## 涵盖路径与体验

- POST /knowledge/assistant/answer：原文来源重新授权后登记一次 Chat 调用，满额就拒绝，模型不会收到正文。
- POST /knowledge/assistant/ask：开始自动搜索前先检查 Chat 是否还有余额，避免已经满额时浪费 Embedding 请求；Chat 请求本身仍以最终原子预约为准。
- POST /knowledge/semantic/search：有该用户同意并已建立语义索引、且将实际调用 Embedding 时预约一次。
- Worker 索引：为每个**需要新模型计算**的片段预约一次；已有同模型且维度一致的向量复用不重复扣除。超过额度时任务回到 PENDING 并延后一小时重试，不烧光正常解析重试次数。
- GET /knowledge/assistant/usage：不缓存的当日 Chat/Embedding 已用次数、配置上限和剩余次数；资料工作台展示与手动刷新。
- 错误码 700005：当日模型次数已达上限，HTTP 429。

数据库故障时模型预约失败，因此默认不再联系外部模型（fail-closed）。额度在 UTC 00:00 更新，不依赖 Redis TTL 或当前节点本地时区。当前只保证上限，不保证问答或向量索引业务的质量与所有可能的上游供应商收费一致。

## 验证

- Maven verify 包含 MySQL 并发 24 路争用仅允许有限调用、不同用户与不同模型额度独立、额度耗尽前不调用外部模型。
- 前端 npm ci、npm run build。
- 未接入具体付费模型的真实计费报告；应在环境上线前继续做实际提供方联调、取消请求与超时情况下计费核对。

下一阶段适合接入模型 usage 元数据、租户预算和 Qdrant，而不必让文件上传业务直接依赖 AI 服务。
