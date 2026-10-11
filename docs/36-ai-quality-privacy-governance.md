# M4.4 — AI 推荐质量评测与隐私治理

> 本阶段实现的是**合成离线推荐校验**、**不含正文的用户授权事件**，以及**显式审批、默认关闭的保留期清理操作**。它并不自动训练模型、不证明真实模型准确率，也不意味着生产环境已部署 V17 或已执行任何清理。

## 1. 可复现的推荐质量基准

`evaluation/strategy/evaluate_strategy.py` 读取 `evaluation/strategy/synthetic_cases.jsonl` 中的合成样本，独立于外部 AI Provider、账号、数据库和网络运行：

```bash
python3 evaluation/strategy/evaluate_strategy.py
python3 -m unittest discover -s evaluation/tests -v
```

每条样本只包括规范化案例 ID、场景类别、是否具备合法授权和有效源、候选数量（最多 8）、人工标注期望序号、模拟模型输出（`N [S1]` 形式或 `null`）。严格禁止任何文件 ID、用户 ID、文件名、提示词、正文、密钥、请求地址、自由文本额外字段。没有查询真实用户行为或调用有费用模型。

报告分别计数：合格模型请求、无资格请求中已在模型前阻断的数量、违规发给模型的请求数、格式有效的结果数、错误输出、Top-1 匹配比例与有效输出覆盖率。对于 `eligible < 5` 的样本不计算百分比；检测到不具备资格却产生模型输出就返回 `BLOCKED`（CI 阻断）。通过离线合成评测也仅返回 `SYNTHETIC_ONLY`，同时明示 `real_provider_validated:false` 和 `business_slo_verified:false`。

样本是用于**程序契约与拒绝路径校验**的固定例题，并非真实模型推理输出或有统计代表性的质量数据。生产推断质量、引用一致性和真实拒绝比例必须在获得数据授权的独立评测环境、结合人工金标准和合理样本量另行验收。

## 2. 用户可见的模型授权记录

后端新增：

```http
GET /api/v1/knowledge/assistant/strategy/consent-history
Authorization: Bearer <当前用户登录令牌>
```

数据只来自当前用户，最多返回最近 30 条，强制 `Cache-Control: no-store`。在 **AI 工作台 → 搜索与问答 → AI 策略推荐** 下展开授权操作记录即可查看。

只记录固定的 `CONSENT_GRANTED`（候选模板和来源均已通过初步权限预检，准备调用模型）、`RECOMMENDED`（成功建立推荐）或 `REQUEST_FAILED`（后续模型/推荐请求未完成），加候选数量 1–8 和数据库时间戳。**不记录原文、提示词、文件名、逻辑文件 ID、候选路径、模型响应、Provider API URL 或任意异常信息**。

无授权、错误参数、跨账号模板、被回收的文档、错误 MIME 类型等前置校验失败时，系统直接阻断，不会误记成“已授权发送”；真正的模型每日预算和按来源版本的权限复核仍由 M4.3 原有流程负责。授权记录表示**向当前配置的模型发起尝试**，不是证明第三方已成功处理或删除输入文本。第三方模型数据保留由其合同、部署配置与服务条款约束，需要另行审查。

## 3. 过期反馈和授权元数据保留期

Flyway **V17** 新增两张独立表：

- `ai_strategy_consent_audit`：用户维度的固定事件码、候选数量和时间；不含源引用及模型正文。
- `ai_strategy_retention_run`：管理员操作人 ID、受控变更单编号、每类清理数量与时间。该表是操作证据，**本阶段没有定时清理该表**。

| 数据类别 | 可清理的历史范围 | 限制 |
| --- | --- | --- |
| `ai_strategy_recommendation` 派生推荐/反馈映射 | 超过 90 天 | 每次最多 100 行，原始 `ai_move_proposal` 不变 |
| `ai_strategy_consent_audit` 模型授权元数据 | 超过 180 天 | 每次最多 100 行，不涉及原文 |
| 用户文件/实际移动提议/Agent 工作流/模型额度 | **本接口不能清理** | 延续原本的权限和保留契约 |

默认**没有定时作业**，且 `AI_STRATEGY_RETENTION_APPLY_ENABLED=false`。因此“90/180 天”是**可申请执行清理的下界，而非保证会在到期日自动清除**。使用者若对历史清理有专门合规或法律要求，还应建立独立的数据主体请求和留存审查流程。

管理员专用接口：

```http
GET /api/v1/admin/strategy-retention/preview
Authorization: Bearer <管理员令牌>
```

返回截止目前已过期候选数、每表最大单次处理 100 行，以及是否允许执行。其返回值仅为计数，不包含历史原文或个人信息。

实际操作必须由受保护生产环境的人员先完成备份、查看 dry-run、创建经批准的变更工单；再明确将 `AI_STRATEGY_RETENTION_APPLY_ENABLED=true` 用于受控操作窗口，并发送：

```http
POST /api/v1/admin/strategy-retention/purge
Authorization: Bearer <管理员令牌>
Content-Type: application/json

{"confirmed":true,"changeTicket":"CHANGE-20261011-001"}
```

API 通过既有 `/api/v1/admin/**` ADMIN 角色保护，另须服务端开关和合法变更单（仅验证格式，不接外部审批系统）。一次处理限制为每类不超过 100 条**真正超过保留期**的记录，并把变更单与计数作为一个数据库事务写入审计。单次失败则事务回滚。若仍有过期数据，需要新的人工决定后再次执行；没有任何无人审批的批量循环、自动恢复或文件内容清除操作。

**清理前必须核对法律留存、监管锁定、备份保留与正在调查的事件。** 数据库清理不等于备份中同步移除历史数据；备份和日志仍按各自的保留及删除政策处理。审计事件仅用于说明本地代码路径，不提供跨系统不可抵赖保证。

## 4. 发布门禁与测试

```bash
python3 -m unittest discover -s deploy/preflight/tests -v
python3 -m unittest discover -s evaluation/tests -v
python3 evaluation/strategy/evaluate_strategy.py
mvn -B verify
cd cloudchunk-web && npm ci && npm run build
```

`deploy/preflight/check_release.py --phase ready` 除 V13–V16 外，还要求 V17 已成功应用且隐私治理表存在。后端测试覆盖权限隔离、独立授权事件、默认关闭的治理操作、明示确认以及真实 MySQL 中较旧元数据和较新元数据的界限；原有 Docker 备份/恢复、Redis/MinIO/RocketMQ 故障注入和合成告警送达继续生效。

**重要：**本文所有 GitHub Actions 测试只在临时数据库和合成样本内执行，没有清理生产记录，没有生产模型调用。部署 V17 之前必须执行受控备份、审批与生产等价账号验收；不能仅依赖 CI 绿色宣称生产合规完成。

## 5. 未完成的治理能力

目前尚未实现真实模型评分语料库、人工复核流程与置信区间、正式的外部数据主体删除工作流、备份级联删除、全局不可变审计系统或模型提供商的数据保留审计。后续 M4.5 适合围绕评测样本版本化和授权生命周期继续推进；这些能力不能仅凭少量合成样本或元数据记录推断已经完成。
