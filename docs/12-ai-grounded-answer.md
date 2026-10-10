# AI-Native M2.1：选定证据的可选模型问答

## 功能边界

本版本保留 M1 的关键词检索。用户先搜索，然后从检索结果中选 1–6 个片段，输入 4–500 字的问题，明确勾选单次发送授权，才能向管理员设置的 OpenAI-compatible Chat Completions 模型发送请求。**未配置时完全关闭，仍可正常检索和阅读原文。**

模型只能看到本次问题及所选片段的文件名/正文（单片段最多 1200 字符）；没有数据库、目录、文件读写等工具。回答须包含 [S1] 等有效来源编号，界面展示并允许点击回原文。服务器拒绝未引用、越界引用或过长回答，允许固定的“资料不足，无法回答。”。引用编号校验不意味着事实判断已经被验证。

当前不是向量语义搜索，不自动扫描全盘资料，不保证检索结果完整；特别是“统计所有”“找出全部变化”等任务不能依据这 6 个片段给出完整结论。

## 管理员配置（API 进程）

默认全部关闭；由部署管理员选择受信任的兼容模型服务：

- AI_CHAT_ENABLED=true
- AI_CHAT_ENDPOINT=https://trusted-gateway.example/v1/chat/completions
- AI_CHAT_MODEL=your-model
- AI_CHAT_API_KEY=...（可选，不进入浏览器）
- AI_CHAT_ALLOW_HTTP=false（仅在完全信任 LAN 模型服务时改 true；对外强烈建议 HTTPS）

浏览器逐次授权发送所选正文，可能流向第三方。请审阅提供方保留与训练政策，并为上游模型设置成本/Token 配额。请求使用 4s 连接超时、20s 请求超时、32KiB 响应上限、600 输出 Token 限制；不跟随 HTTP 重定向，模型异常不向用户返回上游原始错误。Redis per-user 限流是 1 次/秒、突发 2，但不是日预算硬上限。

## API / 权限

GET /api/v1/knowledge/assistant/capabilities 返回是否已启用。
POST /api/v1/knowledge/assistant/answer 接受 question、consent=true、sources[]（userFileId、generation、chunkIndex）。

用户身份从后端认证上下文中获取；服务器先按实时 user_file / file_meta / knowledge_document 状态读取精确版本内容，拒绝客户端提交原文或物理 fileId。模型返回后再次检查引用的文件权限与内容，防止回答在权限撤销或重新索引后展示。但**已经向外部模型发送的内容不能追溯收回**。当前 API 返回 no-store，前端不把答案或证据持久化进 localStorage。

错误码：700001 未启用；700002 来源版本/权限失效；700003 提供方不可用；700004 引用格式不合规。

## 风险和验证

解析文档及模型输出都属于不可信输入。工具访问已禁用，模型被指示忽略文档内指令，但提示词注入、引用虽有效却不支持相关断言、模型偏见和数据保留问题仍存在。重大结论需要人工核对。

新增单测覆盖默认关闭、未经同意拒绝发送、权限拒绝、未知/无效引用和模型推理期间撤销权限。CI 使用已有 Maven verify 和 npm build。真实模型兼容性、上下文质量、模型预算和高并发行为需要单独环境验证；下阶段再做向量/混合检索。
