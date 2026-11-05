# M4.1：受控多步骤 Agent 整理工作流

> **交付范围：**把 M3.1/M4.0 已有的 AI 文件移动候选建议组织为可恢复的持久执行计划，用户必须逐项批准、独立点击执行。此功能不是自治 Agent、自动整盘分类，也不代表执行过的文件可以无条件回滚。

## 1. 从搜索到可审核计划

进入「AI 智能工作台 → 搜索与问答」，选择命中的不同文件（最多四份），选择当前账号允许的候选文件夹，并**一次性明确授权**让现有模型分别生成移动建议。生成的是既有 `ai_move_proposal` 记录，仍然不执行文件变更。

选择「保存为多步骤执行计划（不执行）」后，前端调用：

```http
POST /api/v1/knowledge/assistant/workflows
Authorization: Bearer <当前用户令牌>
Content-Type: application/json

{"moveProposalIds":["11111111-1111-4111-8111-111111111111"],"confirmed":true}
```

服务端要求 1–4 个不同的规范 UUID；每条已有建议都必须属于同一当前用户、仍为 `PENDING` 且未超过 10 分钟提议有效期；同一个逻辑用户文件不能在同一计划中出现两次。**先锁定并校验全部建议，再向数据库写入计划及步骤**。每条提议至多引用进一个执行计划，数据库有唯一索引，避免计划重放、双击、并发重复入库；只创建计划本身不会调用 AI、移动文件或消耗新的模型配额。

`ai_agent_workflow`、`ai_agent_workflow_step`、`ai_agent_workflow_event` 由 Flyway V14 新增。计划仅保存已有建议 UUID、固定步骤序号、预定义状态、拥有者与审计动作；**不会复制文档全文、模型提示词或对象存储路径**。

## 2. 单步动作和安全状态机

计划可以在「AI 智能工作台 → 整理记录 → 多步骤整理计划」中重新打开。当前提供：

| 动作 | 请求（均须当前登录用户与确认） | 结果 |
| --- | --- | --- |
| 查看计划列表 | `GET /assistant/workflows` | 当前账号最近 20 个计划，非公共数据 |
| 详情/审计 | `GET /assistant/workflows/{id}` | 各步状态与最近 40 条有限审计事件 |
| 审核批准 | `POST /assistant/workflows/{id}/steps/{n}/approve` | `PENDING → APPROVED`，文件不变 |
| 人工执行 | `POST /assistant/workflows/{id}/steps/{n}/execute` | `APPROVED → APPLIED`；调用原有移动服务 |
| 跳过当前项 | `POST /assistant/workflows/{id}/steps/{n}/skip` | `PENDING/APPROVED → SKIPPED`，文件不变 |
| 条件撤销 | `POST /assistant/workflows/{id}/steps/{n}/undo` | `APPLIED → REVERTED`；原有严格撤销校验 |
| 取消剩余计划 | `POST /assistant/workflows/{id}/cancel` | 计划 `ACTIVE → CANCELLED`；已执行文件不自动撤销 |

上述 **POST 每次请求体都必须包含 `{"confirmed":true}`**，并且网页在批准、执行、跳过、撤销和取消之前分别要求明确确认。不会自动把第一步的批准传递给第二步。

每个计划的步骤必须按顺序推进。只有上一项是 `APPLIED`、`SKIPPED` 或 `REVERTED`，下一项才允许进入审核阶段；提交越序步骤、重复批准、执行未经批准的步骤、过期建议、其他用户的计划或取消后的计划均直接拒绝。计划完成后不能再改写成 `CANCELLED`。

### 执行不是无条件成功

真正移动时，不信任计划里旧的数据：服务器重新调用 `AiMoveAgentService.apply`，由该服务重新锁定并检查提议状态、当前文件所有权、活跃状态、知识索引 generation、目标目录更新状态、同名冲突与原有事务不变量。任何一步失效都不会被标记为 `APPLIED`，不会顺带执行下一项；用户可以查看原因、刷新文件状态后选择跳过。

`undo` 调用的是之前已有的 `AiMoveAgentService.undo`，要求执行后 15 分钟内、文件及目标目录仍符合历史状态、没有后续修改及同名冲突。**取消计划不等于撤销已移动的文件。** 已执行步骤可以尝试单独撤销，失败不覆盖用户之后的操作。已完成的步骤不会因为后来某一项失败而自动级联回滚。

所有动作共享事务性数据库锁与审计记录。审计仅包含计划 ID、当前登录用户 ID、步骤号、限定的动作代码及数据库时间戳，不保存原始模型回答、文件内容、密钥。无操作权限的人不能查看其他用户的计划或事件。

## 3. 数据库迁移与发布门禁

本版本引入**追加式**数据库 Flyway V14。生产部署前先做 M3.5 受控备份，使用批准的迁移步骤升级至 V14，再放行新 API/Web；不要在还使用 V13 的库上直接切流到 M4.1。

`deploy/preflight/check_release.py --phase ready` 更新为只读要求：

- Flyway V13 目录唯一索引和生成列仍存在；
- **Flyway V14 成功记录**存在；
- 三张 Agent 计划 / 步骤 / 审计表完整；
- `uk_agent_workflow_move` 唯一索引存在；
- 既有 HTTP 健康、出错迁移阻断与用户目录重名预检继续生效。

回滚仍遵守 M3.5 的镜像兼容性审批，**不得**因本次迁移自动删除 V14 表、清空工作流状态或回滚文件系统。

## 4. 验证矩阵

```bash
python3 -m unittest discover -s deploy/preflight/tests -v
python3 -m unittest discover -s deploy/release/tests -v
mvn -B verify
cd cloudchunk-web && npm ci && npm run build
```

CI 的实际 MySQL Testcontainers 集成用例涵盖：计划创建前混入他人的建议应整批失败、计划在不同账号之间不可读取、下一步在上一项未完成前不可批准、审批与真正执行是两个动作、跳过/撤销后才推进下一项、取消计划后不再允许新批准、已执行步骤的撤销走旧服务，以及固定动作的审计记录。

生产等价隔离环境的人工验收还需要：登录两个账号、尝试重放/跨账户计划、等待建议过期再执行、故意在批准后重命名/移动目标文件夹或制造同名冲突、同时提交两次执行、检查日志与文件状态一致、撤销时间窗前后行为、刷新/重开页面后计划仍能显示。

## 5. 明确不做的事

当前工作流只编排**文件移动**，不是重命名+移动+删除的通用脚本执行器，也不包含生产自动 Agent 定时任务；没有多 Agent 自主行动、静默批量审批、跨账号文件修改、自动删除文件、第三方开放工具调用、失败时整批强行恢复。未来扩展步骤类型之前，需要独立的权限和数据契约、冲突与撤销模型、更多审计及隔离集成回归。
