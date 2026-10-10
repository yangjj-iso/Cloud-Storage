import { useEffect, useRef, useState, type FormEvent } from 'react';
import { knowledgeApi, type AutoAskResponse, type Evidence } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

/**
 * A no-surprise shortcut: it appears only when chat is enabled. Every invocation
 * requires fresh consent to query embedding and forward up to four evidence excerpts.
 */
export function DirectAskPanel({ onSourceOpen }: { onSourceOpen: (source: Evidence) => void }) {
  const [enabled, setEnabled] = useState(false);
  const [question, setQuestion] = useState('');
  const [consent, setConsent] = useState(false);
  const [response, setResponse] = useState<AutoAskResponse | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState('');
  const active = useRef<AbortController | null>(null);
  useEffect(() => {
    const req = new AbortController();
    void knowledgeApi.assistantCapabilities(req.signal)
      .then(result => { if (!req.signal.aborted) setEnabled(result.enabled); })
      .catch(() => { if (!req.signal.aborted) setEnabled(false); });
    return () => { req.abort(); active.current?.abort(); };
  }, []);

  const ask = async (event: FormEvent) => {
    event.preventDefault();
    const value = question.trim();
    if (!enabled || !consent || value.length < 4 || value.length > 100) return;
    active.current?.abort();
    const request = new AbortController();
    active.current = request;
    // Consent is for one submission only, including when a provider fails.
    setConsent(false); setResponse(null); setError(''); setRunning(true);
    const timeout = window.setTimeout(() => request.abort(), 48000);
    try {
      const result = await knowledgeApi.autoAsk(value, true, request.signal);
      if (active.current === request && !request.signal.aborted) setResponse(result);
    } catch (cause) {
      if (active.current === request) setError(request.signal.aborted
        ? '问答超时，请稍后重试'
        : cause instanceof Error ? cause.message : '问答请求失败');
    } finally {
      window.clearTimeout(timeout);
      if (active.current === request) setRunning(false);
    }
  };

  if (!enabled) return null;
  return <section className="kw-direct-ask" aria-label="直接向资料提问">
    <div className="kw-eyebrow"><MaterialIcon>auto_awesome</MaterialIcon>一站式资料问答</div>
    <h3>直接提问，自动检索来源</h3>
    <p className="kw-footnote">从当前可访问的资料中检索，自动选取最多 4 段证据；可能使用你已经授权建立的语义索引。
      回答和来源编号需要人工核对，不适用于要求“找全所有文件”的任务。</p>
    <form onSubmit={ask}>
      <label className="sr-only" htmlFor="kw-direct-question">向已索引的资料提问</label>
      <textarea id="kw-direct-question" value={question} rows={2} maxLength={100}
        placeholder="例如：系统如何保证分片上传后的完整性？"
        onChange={event => {
          active.current?.abort(); active.current = null;
          setQuestion(event.target.value); setResponse(null); setError(''); setRunning(false);
        }} />
      <label className="kw-consent">
        <input type="checkbox" checked={consent} onChange={event => setConsent(event.target.checked)} />
        <span>我同意将本次问题发送给部署方配置的 Embedding 模型（若启用），并将自动选中的最多 4 段资料正文及问题发送给问答模型；模型可能在外部。</span>
      </label>
      <div className="kw-answer-actions">
        <span>每次需重新授权 · 不会修改文件</span>
        <button type="submit" disabled={running || !consent || question.trim().length < 4}>
          {running ? '正在检索并回答…' : '从资料中寻找答案'}
        </button>
      </div>
    </form>
    {error && <div className="kw-error" role="alert">{error}</div>}
    {response && <div className="kw-answer-result" role="status">
      <h4>{response.response.mode === 'INSUFFICIENT_EVIDENCE' ? '当前资料不足' : 'AI 生成的回答'}</h4>
      <p className="kw-answer-text">{response.response.answer}</p>
      <p className="kw-footnote">本次选择 {response.selectedCount} 段候选证据 · 检索方式：{({
        HYBRID_RRF_LIMITED:'小范围关键词+向量融合',
        LITERAL_KEYWORD:'关键词',
        SEMANTIC_DISABLED:'关键词（语义关闭）',
        SEMANTIC_NOT_INDEXED:'关键词（暂无语义索引）',
        KEYWORD_FALLBACK:'关键词（语义服务降级）'
      } as Record<string,string>)[response.retrievalMode] ?? response.retrievalMode}</p>
      {response.response.citations.length > 0 && <div className="kw-answer-sources">
        <strong>点击引用，查看原文</strong>
        {response.response.citations.map(source => <button type="button"
          key={source.citationId} onClick={() => onSourceOpen(source.evidence)}>
          [{source.citationId}] {source.evidence.fileName} · 片段 {source.evidence.chunkIndex + 1}
          <MaterialIcon>north_east</MaterialIcon>
        </button>)}
      </div>}
    </div>}
  </section>;
}
