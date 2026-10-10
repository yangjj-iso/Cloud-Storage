import { useEffect, useState } from 'react';
import { knowledgeApi, type AiUsageSnapshot } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

/** Server-authoritative, UTC-day attempted call counters; no file content is displayed. */
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
    <p>这里限制调用次数，不等于 Token 或金额结算；管理员还需配置模型服务的费用上限。</p>
  </div>;
}
