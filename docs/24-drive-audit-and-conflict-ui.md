# M3.3：文件操作记录、冲突反馈与回归

## 操作审计

旧版 OperationAuditFilter 已将多数变更 HTTP 请求写入 op_log，但标签通常只有 DRIVE_POST 或 KNOWLEDGE_POST。本次按固定 HTTP 方法与路由识别：
- DRIVE_MKDIR、DRIVE_RENAME、DRIVE_MOVE、DRIVE_RECYCLE、DRIVE_RESTORE、DRIVE_PURGE
- SHARE_SAVE
- AI_RENAME_SUGGEST、AI_RENAME_APPLY、AI_MOVE_SUGGEST（包括批量预览）、AI_MOVE_APPLY、AI_MOVE_UNDO

成功或失败由实际 HTTP 状态和完成情况决定。记录的字段沿用原有 op_log（用户、操作、时间、耗时、结果），不采集请求体、模型消息、文件正文，也不把文件名加到监控标签。旧泛化日志不会被转换或伪装成细分操作事件。

新增 GET /api/v1/drive/audit/recent，只读且登录必需，Cache-Control: no-store；以 user_id 查询最新30条受支持的分类事件，响应仅包含事件ID、操作类型、成功状态、时间和耗时，不回传 target_id 原始请求路径或文件名。页面可主动展开、刷新记录。这个能力是原有 op_log 的尽力 HTTP 审计，不是不可篡改账本；日志写入失败不阻止业务事务，关键监管场景还需要事务性领域事件及专门追加存储。

## 重名冲突体验

M3.2 已统一同目录文件与文件夹的数据库唯一约束，冲突返回 300004 / HTTP 409。本轮为前端 API 增加明确中文提示。创建目录、重命名及移动操作会将异步成功/失败传递给界面：失败时保留正在编辑的文本或目标文件夹窗口，展示提醒，不会默默关闭。对于当前目录里已经存在的同名文件名，页面也会本地预警；但最终冲突判断仍以 MySQL 唯一索引和后端 409 为准。

旧网盘路径和 AI 人工批准操作的权限/事务保持不变。没有自动删除、覆盖、修改用户其他文件或重试高风险操作。

## 回归和上线说明

- DriveOperationHistoryTest 验证所有明确操作路由/方法与无关路径分类，不让任意路由假装成 AI 已批准操作。
- DriveNameUniquenessIT 的真实 MySQL 8 测试验证按 user_id 过滤已记录的成功/失败事件，并继续运行原有同目录唯一性和并发创建/自动导入测试。
- 既有知识隔离、AI 单次确认/撤销、Qdrant Testcontainers、中文检索评测、React 构建和 Maven verify 均保留在 CI。
- 本版本无 Flyway 新迁移，直接复用 op_log；上线仍须先按 docs/23 的只读 SQL 处理历史同目录重名，再执行 M3.2 的 Flyway V13。
- CI 通过与合并不等于已经部署生产。前端冲突保留的是当前输入状态，不是文件自动恢复；用户仍需核对目标名称与权限。
