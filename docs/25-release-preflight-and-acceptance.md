# M3.4 上线准备：只读预检、V13 迁移保护、隔离账号验收

本阶段新增的是上线验证工具与 CI 门禁，不是生产部署。**没有运行生产数据库迁移，也没有接触用户账号、文件或真实模型服务。** 已有生产数据可能含 M3.2 引入的同目录活跃重名，Flyway V13 会阻止未经人工处理的数据库升级，不能绕过。

## 一、上线前备份与只读检查

在具有 SELECT 权限的数据库账号、装有 MySQL 8 客户端的运维主机上设置连接环境变量（不要在 shell 历史或 CI 日志里输出明文密码）：

    export MYSQL_HOST=127.0.0.1
    export MYSQL_PORT=3306
    export MYSQL_USER=cloudchunk_preflight
    export MYSQL_DB=cloudchunk
    export MYSQL_PASSWORD='从安全凭据管理系统注入，不要复制到日志'

先运行：

    python3 deploy/preflight/check_release.py --phase before-v13

此命令只使用 MySQL SELECT，检查：
- 同一用户、同一父目录中按照 utf8mb4_unicode_ci 的活跃同名**冲突组数量必须为 0**；
- Flyway 历史中不存在失败迁移；
- 显示 Flyway V13 是否已经成功应用，但在升级前阶段不强制 V13 已存在。

输出是仅含数量和有限状态的 JSON，不包含用户 ID、文件名、文件正文、数据库密码或物理路径。任何查询异常、缺失历史表、含重名或失败迁移都返回非零退出码，阻止自动部署。

若发现历史同名，先只读运行 docs/23-drive-sibling-name-invariants.md 中的详细 SQL，由管理员与文件所属用户协商选择哪条改名或移动。绝对不要让预检自动解决冲突。先完成离线备份和恢复演练，安排 MySQL V13 的表结构变更维护窗口，确认业务兼容性后再走正常发布流程。

## 二、V13 后的就绪核验

迁移和 API/Worker 部署完成之后，再执行：

    python3 deploy/preflight/check_release.py --phase ready --health-base https://cloud.example.com

就绪要求：
- 没有活跃同目录重名；
- 没有失败 Flyway 记录，且版本 13 在历史中为成功；
- user_file 含生成列 active_file_name；
- 数据库 UNIQUE 索引 uk_drive_active_sibling 含三个列；
- 指定 --health-base 时 /actuator/health 返回 {"status":"UP"} 且 /api/v1/ping 返回 code=0/data.status=UP。

健康 URL 只允许 HTTPS，或明确的 localhost/127.0.0.1 回环 HTTP。不需要放宽生产健康端点的访问控制：无法从运维主机访问时就绪检查会阻断，需改为从可信网络执行，不要为了通过预检开放公网管理端点。

此检查不验证真实 RocketMQ 业务事件消费、真实对象存储字节或已开启的 AI 模型配置，不能替代负载测试、备份/回滚演练。生产应将数据库预检设为人工批准上线门禁，而不是无保护地在应用启动时尝试运行未知 DDL。

## 三、隔离账号 HTTP 端到端验收

明确在临时部署、**一次性测试账号**（无真实用户数据）运行：

    export CLOUDCHUNK_SMOKE_TOKEN='从临时账号登录后获得的短期 token'
    export CLOUDCHUNK_SMOKE_ALLOW_MUTATION=I_UNDERSTAND_TEST_ACCOUNT
    python3 deploy/preflight/drive_smoke.py --base-url http://127.0.0.1:8080

必须显式指定高风险开关和测试账号 token；脚本只允许 HTTPS 或回环 HTTP。

验收会：
1. 创建随机前缀（cc-smoke-*）的测试根目录与嵌套子目录；
2. 验证同目录大小写不同名冲突返回 HTTP 409 / 300004；
3. 对自己刚创建的节点执行重命名、移动，并 GET /drive/list 确认新位置；
4. 将自己的节点移入回收站、恢复并重新确认存在；
5. 查询 GET /drive/audit，检查当前账号最近操作至少包含对应动作分类；
6. finally 块中仅对自己新建的测试根目录执行回收站和彻底删除，失败则返回非零状态并显示需人工清理的测试根目录编号。

脚本**绝不**删除根目录以外节点，不读取真实文档正文，不发起 AI 推理或其他外部模型调用。仅限隔离环境与空测试账号；不能当作生产真实数据的验收脚本。脚本不自动取得或刷新用户 Token，不记录 Token 值。

若脚本失败，停止发布。人工核实日志、持久化结果与原始文件情况之后再尝试；不要通过忽略 409、清理真实数据或取消唯一索引来强行通过。

## 四、CI 门禁及边界

- Python 标准库测试验证 preflight 失败封闭语义、MySQL 执行参数安全、输出解析，以及 HTTP smoke 的模拟流程和失败清理。
- Maven verify 新增 DriveUpgradePreflightIT：真实 MySQL 8 从 V12 尝试升级 V13，**主动构造不区分大小写的同目录重名**，确认迁移失败且用户记录保留；人工处理冲突后 repair + migrate 成功，数据库 UNIQUE 索引阻止后续重名。
- 保留现有真实 MySQL 文件操作/权限测试、Qdrant 容器测试、React build 与中文检索评测测试。

CI 不运行 live HTTP smoke 以免在测试工作流中发生未经授权的文件变更；它必须由部署负责人使用专用账号显式触发。上线后的长期生产指标、跨版本兼容性、灾难恢复和外部 AI 提供方验证仍需专门演练。
