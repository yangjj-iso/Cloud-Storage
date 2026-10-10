import { useEffect, useState } from 'react';
import { knowledgeApi, type AiUsageSnapshot } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

/** Only authenticated user usage counters; no source text is retained. */
export function AiUsageBar() {
  const [usage, setUsage] = useState<AiUsageSnapshot | null>(null);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const ctrl = new AbortController();
    void knowledgeApi.aiUsage(ctrl.signal)
      .then(data => { if (!ctrl.signal.aborted) { setUsage(data); setError(''); }})
      .catch(() => { if (!ctrl.signal.aborted) setError('模型配额暂不可用'); });
    return () => ctrl.abort();
  }, [revision]);

  return <div className="kw-usage" aria-label="模型每日调用配额">
    <div className="kw-usage-top">
      <span><MaterialIcon>gauge</MaterialIcon> 每日模型调用额度</span>
      <button type="button" onClick={() => setRevision(x => x + 1)} aria-label="刷新模型额度">
        <MaterialIcon>refresh</MaterialIcon>
      </button>
    </div>
    {usage ? <div className="kw-usage-stats">
      <span>问答 <strong>{usage.chat.used} / {usage.chat.limit}</strong> 次</span>
      <span>向量 <strong>{usage.embedding.used} / {usage.embedding.limit}</strong> 次</span>
      <span>UTC {usage.dayUtc} · 失败的模型请求也计入额度</span>
    </div> : <span className="kw-usage-hint">{error || '正在读取剩余额度…'}</span>}
    {usage && <div className="kw-token-usage">
      <span>已报告 Token：问答输入 {usage.chat.inputTokens.toLocaleString()} / 输出 {usage.chat.outputTokens.toLocaleString()}</span>
      <span>Embedding 输入 {usage.embedding.inputTokens.toLocaleString()} Token</span>
      <span>带使用量数据的调用数：问答 {usage.chat.reportedCalls}/{usage.chat.used} · Embedding {usage.embedding.reportedCalls}/{usage.embedding.used}</span>
      {(usage.chat.estimatedUsd !== null || usage.embedding.estimatedUsd !== null) &&
        <span>按当前单价估算（USD）：
          {usage.chat.estimatedUsd !== null ? ' 问答 ' + usage.chat.estimatedUsd : ' 问答未知'}
          {usage.embedding.estimatedUsd !== null ? ' · Embedding ' + usage.embedding.estimatedUsd : ' · Embedding 未知'}
        </span>}
    </div>}
    <p>仅统计提供方返回的 Token；缺少使用量字段或失败的请求不代表免费。估算值不是账单或金额硬上限。</p>
  </div>;
}
