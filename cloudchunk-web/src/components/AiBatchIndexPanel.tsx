import { useEffect, useRef, useState } from 'react';
import { knowledgeApi, statusLabels, type KnowledgeDocument, type Library } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

const isIndexable = (doc: KnowledgeDocument) => doc.status === 'NOT_INDEXED' || doc.status === 'FAILED';

/** Atomic, user-authorized extraction queueing; no model calls or file changes. */
export function AiBatchIndexPanel({ onExplore }: { onExplore: () => void }) {
  const [page, setPage] = useState(1);
  const [library, setLibrary] = useState<Library | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);
  const action = useRef<AbortController | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setLibrary(null); setError(''); setSelected([]);
    void knowledgeApi.library(page, controller.signal)
      .then(value => { if (!controller.signal.aborted) setLibrary(value); })
      .catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : '资料列表不可用'); });
    return () => controller.abort();
  }, [page, revision]);
  useEffect(() => () => action.current?.abort(), []);

  const toggle = (id: string) => {
    setSelected(current => current.includes(id)
      ? current.filter(item => item !== id)
      : current.length < 8 ? [...current, id] : current);
    setNotice('');
  };
  const submit = async () => {
    if (busy || selected.length === 0) return;
    const confirmed = window.confirm(`为当前账号的 ${selected.length} 份文档提交解析索引请求？此步骤不调用 AI 模型，不会重命名或移动文件。`);
    if (!confirmed) return;
    const ctrl = new AbortController();
    action.current = ctrl;
    setBusy(true); setError(''); setNotice('');
    try {
      const result = await knowledgeApi.indexBatch([...selected], ctrl.signal);
      if (!ctrl.signal.aborted) {
        setNotice(`已受理 ${result.accepted} 份资料的索引检查，已排队或完成的任务不会重复提交。请等待后台解析后刷新。`);
        setSelected([]); setRevision(n => n + 1);
      }
    } catch (cause) {
      if (!ctrl.signal.aborted) setError(cause instanceof Error ? cause.message : '提交失败，请刷新资料权限后重试');
    } finally {
      if (action.current === ctrl) { action.current = null; setBusy(false); }
    }
  };
  const docs = library?.documents ?? [];
  const eligible = docs.filter(isIndexable);
  return <section className="ai-batch-index">
    <div className="ai-hub-section-head">
      <div><span className="ai-hub-eyebrow">KNOWLEDGE PREPARATION</span>
        <h2>把文档变成可查找的知识</h2>
        <p>仅限当前账号可访问且已完成完整性校验的文档。最多一次选择 8 份，服务器会在创建任何任务前验证全部权限。</p></div>
      <button type="button" className="ai-hub-outline" onClick={() => setRevision(v => v + 1)} disabled={busy}>
        <MaterialIcon>refresh</MaterialIcon>刷新列表</button>
    </div>
    <div className="ai-batch-toolbar">
      <span><MaterialIcon>info</MaterialIcon> 建立的是本地文本解析索引；Embedding 需要以后逐文件另行授权。</span>
      <span>当前选中 <strong>{selected.length}/8</strong> 份</span>
    </div>
    {error && <div className="ai-hub-notice" role="alert">{error}</div>}
    {notice && <div className="ai-hub-success" role="status">{notice}</div>}
    {!library && !error && <div className="ai-hub-loading">正在按你的文件权限获取资料…</div>}
    {library && (
      <>
        {docs.length === 0 && <div className="ai-hub-loading">这一页还没有支持的文档。你可以先上传 TXT、Markdown、文本型 PDF 或 DOCX。</div>}
        {docs.length > 0 && <div className="ai-batch-table">
          {docs.map(doc => <label key={doc.userFileId} className={`ai-batch-row ${isIndexable(doc) ? '' : 'ai-batch-row-done'}`}>
            <input type="checkbox" disabled={!isIndexable(doc) || busy || (!selected.includes(doc.userFileId) && selected.length >= 8)}
              checked={selected.includes(doc.userFileId)} onChange={() => toggle(doc.userFileId)} />
            <span className="ai-batch-file-icon"><MaterialIcon>description</MaterialIcon></span>
            <span className="ai-batch-file"><strong title={doc.fileName}>{doc.fileName}</strong>
              <small>{doc.mimeType} · {doc.chunkCount || 0} 片段</small></span>
            <span className="ai-batch-status">{statusLabels[doc.status] ?? doc.status}</span>
          </label>)}
        </div>}
        <div className="ai-batch-footer">
          <span>第 {page} 页 · 当前页可提交 {eligible.length} 份 · 翻页会清空选择</span>
          <div className="ai-batch-footer-buttons">
            <button type="button" onClick={() => setPage(v => v - 1)} disabled={busy || page <= 1} className="ai-hub-outline">上一页</button>
            <button type="button" onClick={() => setPage(v => v + 1)} disabled={busy || !library.hasMore} className="ai-hub-outline">下一页</button>
            <button type="button" disabled={busy || selected.length === 0} onClick={() => void submit()} className="ai-hub-primary">
              <MaterialIcon>playlist_add_check</MaterialIcon>{busy ? '提交中…' : `为 ${selected.length} 份资料建索引`}
            </button>
          </div>
        </div>
      </>
    )}
    <div className="ai-hub-safety">
      <MaterialIcon>shield_lock</MaterialIcon><div>
        <strong>权限先于索引</strong><p>任何文件失去授权、被移入回收站、未完成上传或参数重复，都将拒绝整批提交。此操作不会向第三方模型发送正文。</p>
        <button type="button" className="ai-hub-text-link" onClick={onExplore}>已经建立好索引？前往搜索与问答 <MaterialIcon>arrow_forward</MaterialIcon></button>
      </div>
    </div>
  </section>;
}
