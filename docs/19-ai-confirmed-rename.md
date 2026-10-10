# M2.8：受控 AI 文件操作（单文件建议重命名）

第一步仅提供“根据已获权限的原文片段建议文件名 → 预览 → 用户二次确认 → 原业务重命名”。模型没有文件系统工具，不支持自动执行、批量移动、删除或创建文件夹。

默认关闭：AI_AGENT_RENAME_ENABLED=false。管理员须另外配置已启用的 Chat 模型及每日调用预算。只对已解析、当前可读的单个普通文件提供建议；用户勾选授权后才会发送最多 1200 字符的一个片段给模型，计入 Chat 模型调用额度与实际返回的 Token 使用量。

## API

- GET /api/v1/knowledge/assistant/rename/capabilities：认证用户获知是否可用。
- POST /api/v1/knowledge/assistant/rename/suggest：body 为 source={userFileId,generation,chunkIndex}、consent=true；浏览器不提供任意原文或目标文件名。
- POST /api/v1/knowledge/assistant/rename/{proposalId}/apply：body 为 confirmed=true；依据服务端保存的准确提议执行一次。
- GET /api/v1/knowledge/assistant/rename/recent：只看当前用户最近 10 条建议。

建议输出限定“新文件名 [S1]”格式，必须保留文件扩展名，最多 128 字符，拒绝路径分隔符、非法字符、多行指令、同名和危险文件名。模型建议只是未经验证的数据，不能当作用户授权。

## 权限与审计

服务端建议时使用 KnowledgeService.source 读取当前 user_file 权限、物理文件 AVAILABLE 状态、索引 generation 和精确 chunk。模型返回后重新鉴权并检查原文件名及 updated_at，才把建议保存到 V10 新增的 ai_rename_proposal 表（有效期 10 分钟）。

执行时重新检查 proposal 是否属当前用户、未过期、未使用、confirmed=true，使用 SELECT FOR UPDATE 锁定建议及逻辑文件，复核原文件名、文件更新时间、索引版本、活跃状态、权限及证据存在；最后调用原来的 UserFileService.rename。成功设置 APPLIED/ applied_at；重复提交、其他用户访问、回收站、手工改名或重索引会拒绝。普通 API 的操作审计继续生效，提议表本身也记录前后文件名、用户、状态与执行时间。Worker 保留 90 天的审计元数据后分批清理；不存文档正文或模型输出全文。

注意：旧版普通 rename API 没有同目录名称唯一约束，暂不能保证跨业务高并发的重名争用。M2.8 只是受控执行框架，不应被称为自主 Agent。

## 验证

Flyway V10 迁移；真实 MySQL 测试包括未经授权、跨用户、拒绝执行、二次确认、已用过建议、人工重命名、回收站失效。单测校验模型输出路径和扩展名限制。CI 仍使用 mvn verify 与 npm run build。

未来阶段可以在类似框架上先添加只读的文件夹归类建议，逐项批准后才考虑移动操作，绝不能让模型文本直接触发删除或外部指令执行。
