# M4.5：版本化 AI 评测、授权生命周期与反馈人工复核

本轮承接 M4.3、M4.4；**并非自动学习、历史授权全部撤回或生产模型质量认证**。新功能仅处理当前账号策略推荐和分类反馈，不提供文件读写权限给模型。

## 版本化离线评测

`evaluation/strategy/manifest.v1.json` 钉住合成案例文件的 SHA-256、基准版本 v1、五类必须存在的情景、至少 12 条样本（其中至少 8 条 eligible）及合成 Top-1 下限 0.70。

```bash
python3 evaluation/strategy/evaluate_strategy.py
python3 -m unittest discover -s evaluation/tests -v
```

评测首先核对文件字节哈希与清单；擅自修改或换用未发布的数据集就阻断。校验模型输出固定序号格式、候选范围和未授权情形的阻断逻辑。报告始终包含 `real_provider_validated=false` 和 `business_slo_verified=false`，不冒充实际模型准确率、正式基准或用户真实反馈。修改样本必须建立新审核版本并重新固定哈希。

## 可随时关闭未来的策略推荐

用户登录后进入 **AI 工作台 → AI 隐私控制**。GET/POST `/api/v1/knowledge/assistant/strategy/recommendation-policy` 只操作当前账号：

```json
{"allowRecommendations":false,"confirmed":true}
```

新增 `ai_strategy_user_policy`，默认无保存偏好时保留既有“逐次授权”要求；显式关闭后，服务端在策略推荐模型调用前进行检查，在发起前再次确认。重新开启**不会默认同意发送正文**，每次仍需独立勾选并受到预算、ACL 与版本限制。偏好调整写入仅包含固定事件和时间戳的隐私审计（不保存文件 ID 或内容）。

**注意：**已经开始或已发送给模型提供商的请求不能被追溯撤回；此开关只作用于整理策略推荐，独立问答与 Embedding 仍有各自的授权机制。这不是法律数据主体删除或提供商记忆清除功能。

## 来源版本严格绑定

Flyway V18 为 `ai_strategy_recommendation` 增加 `source_generation`。新的推荐记录写入当时的知识解析版本；查看推荐、给出反馈或请求复核，都要重新检查当前用户名下的活跃逻辑文件、AVAILABLE 物理对象与准确的 READY/PARTIAL 知识索引 generation。文件已删除、回收、权限失效或重新索引后，旧推荐不能继续进入这些流程。

历史 V16 推荐没有 source_generation，升级后的旧数据将保守地不能在需要展示文件或执行反馈的入口使用，而不会猜测旧版本。账户级历史分类计数不是证明用户仍拥有原始来源。其他旧的移动提议仍遵循已有的独立确认、过期与来源版本检查。

## 分类信息的人工作业复核

用户可为自己最近 30 天、已明确评价、来源依然有效的推荐，在隐私页申请一次复核：

```http
POST /api/v1/knowledge/assistant/strategy/recommendations/{id}/review
{"confirmed":true}
```

复核状态由 V18 的 `ai_strategy_feedback_review` 保存，数据库唯一约束防止同一推荐重复创建。用户 GET `/api/v1/knowledge/assistant/strategy/feedback-reviews` 只能看到自己最近 20 项受限复核记录。

管理员在管理后台健康页看到**分类信息**的队列（最多 20 项），不能读取文件正文、文件名、模型回答、外部账户或文件路径：

```http
GET /api/v1/admin/strategy-feedback-reviews
POST /api/v1/admin/strategy-feedback-reviews/{id}/resolve
{"reviewCode":"FOLLOWUP_REQUIRED","confirmed":true}
```

状态 `REQUESTED → REVIEWED` 只允许一次；结果仅限 `CONFIRMED`、`FOLLOWUP_REQUIRED`、`INSUFFICIENT_EVIDENCE`。管理员不能处理本人提交的复核。**复核不更改用户原始反馈**，不会修改文件或启动模型。原有 M4.4 超过 90 天的推荐关联清理会通过 FK 级联清除对应复核元数据；清理仍需现有手动审批。

## 数据库与发布

Flyway V18 追加用户策略偏好表、受限反馈复核表以及推荐表的可空来源代际字段，不对旧记录伪造版本。受保护 `deploy/preflight/check_release.py --phase ready` 会检查 V13–V17 原门禁和 V18 是否成功迁移、两张新表、代际列及复核唯一索引。

```bash
python3 -m unittest discover -s deploy/preflight/tests -v
mvn -B verify
cd cloudchunk-web && npm ci && npm run build
```

CI 在合成评测及临时 MySQL/Redis 和隔离 Docker 中执行，涵盖跨账号复核隔离、本人不能复核本人、重复申请/处理阻断、策略开关与旧来源代际失效。生产上线仍须受控备份、批准 V18 迁移、生产等价账号验收和管理员审核。**不会直接在生产环境替用户做撤权或数据清理**。

未来可单独增加真实经过授权的人工金标准、模型供应商保留条款核验、全业务的授权生命周期和正式隐私请求处理流程。当前能力不得被解释为完整数据治理或 AI 准确率认证。
