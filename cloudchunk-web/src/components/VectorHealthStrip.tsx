import { useEffect, useState } from 'react';
import { knowledgeApi, type VectorHealth } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

/** User-scoped health; no global counts or file identifiers exposed. */
export function VectorHealthStrip() {
  const [health, setHealth] = useState<VectorHealth | null>(null);
  const [error, setError] = useState(false);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    const reload = () => {
      if (document.hidden) return;
      void knowledgeApi.semanticHealth(controller.signal)
        .then(data => { if (!controller.signal.aborted) { setHealth(data); setError(false); }})
        .catch(() => { if (!controller.signal.aborted) setError(true); });
    };
    reload();
    const timer = window.setInterval(reload, 60000);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, [refresh]);

  const states: Record<VectorHealth['state'], string> = {
    EMBEDDING_DISABLED: '语义模型未开启，使用关键词检索',
    MYSQL_ONLY: '语义检索：MySQL 小规模索引',
    NO_INDEX: '暂无已完成的语义索引',
    SYNCING: 'Qdrant 向量镜像同步中，搜索仍可回退 MySQL',
    READY: 'Qdrant 镜像已就绪，检索可使用向量加速',
    DEGRADED: 'Qdrant 连接或 collection 异常，搜索将回退 MySQL',
  };
  return <div className="kw-vector-health" aria-label="当前账号的语义索引健康状态">
    <MaterialIcon>{health?.state === 'READY' ? 'verified' : 'monitor_heart'}</MaterialIcon>
    <span>{error ? '语义索引状态暂不可用' : health ? states[health.state] : '正在检查语义索引状态…'}</span>
    {health && health.readyDocuments > 0 &&
      <span className="kw-vector-progress">已同步 {health.mirroredDocuments} / {health.readyDocuments} 份</span>}
    <button type="button" title="刷新语义索引状态" aria-label="刷新语义索引状态"
      onClick={() => setRefresh(x => x + 1)}><MaterialIcon>refresh</MaterialIcon></button>
  </div>;
}
