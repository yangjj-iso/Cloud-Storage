# M2.9：受控的 AI 文件归类与二次确认移动

这一阶段扩展 M2.8 的“建议与执行分离”机制：用户从资料工作台选一个已有证据片段，请模型在**当前用户拥有的最多 12 个活跃、不同于当前文件夹的目录**中推荐一个归类位置；不创建文件夹，不自动移动、不删除、不批量操作。

## 默认关闭与发送范围

AI_AGENT_MOVE_ENABLED=false，必须由管理员设置 true 才显示。需要已配置并启用 M2.1 Chat 模型（AI_CHAT_ENABLED=true 等）。用户每次选择并勾选同意才将原文片段（最多 1200 字符）与候选文件夹名称送往该 Chat 服务。该服务可能位于外部，部署管理员需核实提供方数据保留与隐私政策。模型调用继续使用同一每日 Chat 配额和用户级速率限制。

从真实 user_file 所属的活跃目录筛选候选，不接受用户提交的任意目录表。候选列表按 id 排序，最多 12 个；如无可移动的目录，操作拒绝，不会消耗模型费用。模型只需输出“目录序号 [S1]”。服务端只接受合法、存在的候选序号，**绝不直接执行模型文本、路径或模型指定目录 ID**。

## API

- GET /api/v1/knowledge/assistant/move/capabilities
- POST /api/v1/knowledge/assistant/move/suggest，JSON 包含 source={userFileId,generation,chunkIndex}、consent=true
- POST /api/v1/knowledge/assistant/move/{proposalId}/apply，JSON 包含 confirmed=true
- GET /api/v1/knowledge/assistant/move/recent，仅列出当前用户自己的最近 10 个提议记录

只有当前 owner 对正文可见、文件索引状态 READY/PARTIAL 且物理文件 AVAILABLE 才能提出建议。模型返回后，服务端重新读取源片段、逻辑文件版本以及目标目录名称/位置/版本，再在 V11 ai_move_proposal 中存储一次性 UUID 提议，十分钟过期。

用户检查预览的“当前文件 → 原目录 → 目标目录”，再单独点击确认，浏览器会弹出二次确认。后端使用事务、提议 FOR UPDATE、逻辑文件 FOR UPDATE、目录 FOR UPDATE、实时 owner/status/generation/updated_at 检验。重新通过时执行原 UserFileService.move，而非直接修改对象存储或绕过业务的防环、归属校验。成功后状态 APPLIED，仅能执行一次。错用户、回收站、源文件已移动、源文件已重命名、目标目录已修改、过期或重放都直接拒绝。

ai_move_proposal 只保存操作元数据，不保存原文或完整模型文本。既有 OperationAuditFilter 记录变更请求，后台将 90 天前提议元数据分批清理。当前不存在同目录名称唯一约束；此功能不是自主 Agent 或强形式化目录一致性方案，仍需继续测试其他网盘操作并发争用。启用前在测试部署中检查迁移 V11 和权限隔离测试。

## 实现范围与下一步

- **可用**：一个普通文件、一个已存在且当前用户拥有的目录、逐次同意、预览、手动执行。
- **未开放**：一次移动多个文件、创建目录、自动整理、规则化执行、彻底删除、跨用户分享目标。
- 候选目录最多 12 个且按 ID 截断，可能并非最相关目录；后续可增加用户自行挑选候选、排序及文件夹树上下文，但不得让 AI 自行扩大权限。
- 模型选择只是建议，用户需核对目标文件夹和文件名；测试通过不保证真实模型分类准确率。
