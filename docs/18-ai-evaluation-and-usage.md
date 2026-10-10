# M2.7：AI Token 用量、费用估算与中文检索评测

## 提供方 Token 用量

Flyway V9 在 ai_model_daily_usage 中增加三个聚合列：usage_reported_calls、input_tokens、output_tokens。它们只记录模型服务商明确返回的 usage 字段，不存储原文、用户问题、模型回答或凭据。

Chat 从 usage.prompt_tokens 与 usage.completion_tokens 汇总。Embedding 从 usage.prompt_tokens 汇总输入量，输出为零。缺失、负数、非整数或明显过大的计数记为未知，不能被解释为免费。现有 used_calls 是已预约的尝试次数，仍包含失败调用。若后续引用校验未通过，已经成功返回的模型调用仍有可能计入 Token 和费用。

管理员可选设置三项 USD / 百万 Token 单价：
- AI_CHAT_INPUT_USD_PER_MILLION
- AI_CHAT_OUTPUT_USD_PER_MILLION
- AI_EMBEDDING_INPUT_USD_PER_MILLION

设置后可计算基于当前单价的估算值；不设置时 estimatedUsd 为 null。更改单价会按新价格重算当日已报告量。本版本未考虑供应商缓存折扣、并发多模型价格差异或退款，**不代表真实账单，不提供金额硬上限**。应继续在模型网关开启正式付费限制。

## 延迟监控

关键词与混合检索 API 会记录 Micrometer Timer：
- cloudchunk.ai.retrieval.duration，mode 标签只能是 keyword 或 hybrid。
- 提供 P95/P99 和 histogram buckets，供 Prometheus/Grafana 采集。
- 监控只写聚合时延，不以用户、文件、问题或模型服务 URL 做标签。
- 混合检索耗时包含可选的 Embedding 网络请求。

## 中文相关性测试

evaluation/sample_documents/ 包含六篇原创、与真实用户私有数据无关的测试资料，evaluation/chinese_cases.jsonl 包含 12 条人工标注的中文查询和相关文件名。两种检索模式使用各自适用的样例。它们仅用于基础功能回归，不能代替真实用户语料评测。

将六篇 Markdown 上传到隔离测试账号，等正文解析完成。要测试语义模式，还需为各篇资料逐一确认 Embedding 并等待 READY。Token 授权和每日调用上限仍然有效。

可用只针对本地测试环境的命令：

    CLOUDCHUNK_EVAL_TOKEN="临时测试账号token" python3 evaluation/evaluate_retrieval.py --mode keyword --base-url http://127.0.0.1:8080
    CLOUDCHUNK_EVAL_TOKEN="临时测试账号token" python3 evaluation/evaluate_retrieval.py --mode hybrid --base-url http://127.0.0.1:8080

脚本按来源中的 fileName 去重，计算 Recall@5、MRR@10；缺失的案例不会被默默跳过。评测脚本不保存返回的文档正文。也可以提供 --results 路径作为离线 JSONL 文件，每行如：
    {"id":"C01","ranked":["上传完整性.md"]}

GitHub Actions 仅验证评测代码和标注结构，而不模拟实际 Embedding 推理质量；必须在测试部署中执行真实查询后才能获得有意义的相关性成绩。真实大数据集的 Recall/P95/P99 还需要单独的容量与质量验收。

## 未解决事项

升级需执行 V9，但不修改历史迁移文件。跨 UTC 午夜的使用量归属、多供应商独立计价、异常调用重放和与真实支付账单逐笔对账仍待完成。请勿以此页面数值作为付款结算依据。
