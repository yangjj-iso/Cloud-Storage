import { useEffect, useRef, useState } from 'react';
import { knowledgeApi, type Evidence, type ResearchBrief, type SearchResult, type SourceRef } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

const keyFor = (hit: Evidence) => `${hit.userFileId}:${hit.generation}:${hit.chunkIndex}`;
const toRef = (hit: Evidence): SourceRef => ({ userFileId: hit.userFileId, generation: hit.generation, chunkIndex: hit.chunkIndex });

/** Deterministic quotations only: no model calls, no summarization, no external exports. */
export function makeResearchMarkdown(query: string, brief: ResearchBrief): string {
  const safeTitle = (text: string) => text.replace(/[\r\n#*|<>]/g, ' ').slice(0, 120);
  const lines = [
    '# CloudChunk · 研究摘录',
    '',
    '仅摘录当前账号在导出时重新鉴权通过的原文片段；不构成 AI 总结、事实核验或完整搜索结果。',
    '',
    `检索关键词：${safeTitle(query)}`,
    `来源片段：${brief.evidence.length}`,
    '',
  ];
  brief.evidence.forEach((hit, index) => {
    lines.push(`## [S${index + 1}] ${safeTitle(hit.fileName)}`, '');
    lines.push(`解析版本 ${hit.generation} · 片段 ${hit.chunkIndex + 1} · 字符偏移 ${hit.startOffset}–${hit.endOffset}`, '');
    const clipped = hit.content.slice(0, 700);
    lines.push(...clipped.split(/\r?\n/).map(line => '> ' + line));
    if (clipped.length < hit.content.length) lines.push('> …（截取前 700 字符）');
    lines.push('');
  });
  lines.push('---', '请在原文中核验上述引用；文档可能在导出后发生变化。', '');
  return lines.join('\n');
}

export function ResearchBriefPanel({ results, query, onSourceOpen }: {
  results: SearchResult; query: string; onSourceOpen: (hit: Evidence) => void;
}) {
  const hits = results.evidence.slice(0, 20);
  const [selected, setSelected] = useState<string[]>([]);
  const [preview, setPreview] = useState<ResearchBrief | null>(null);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const inFlight = useRef<AbortController | null>(null);
  useEffect(() => () => inFlight.current?.abort(), []);

  const chosen = () => hits.filter(hit => selected.includes(keyFor(hit))).slice(0, 12).map(toRef);
  const toggle = (hit: Evidence) => {
    const key = keyFor(hit);
    inFlight.current?.abort();
    setSelected(previous => previous.includes(key)
      ? previous.filter(item => item !== key)
      : previous.length >= 12 ? previous : [...previous, key]);
    setPreview(null); setError(''); setNotice('');
  };
  const prepare = async (destination?: 'copy' | 'file') => {
    if (working || selected.length === 0) return;
    inFlight.current?.abort();
    const controller = new AbortController();
    inFlight.current = controller;
    setWorking(true); setError(''); setNotice('');
    try {
      // Fresh server-side ACL + generation check for every item, including on copy/download.
      const current = await knowledgeApi.researchBrief(chosen(), controller.signal);
      if (controller.signal.aborted || inFlight.current !== controller) return;
      setPreview(current);
      if (destination) {
        const markdown = makeResearchMarkdown(query, current);
        if (destination === 'copy') {
          await navigator.clipboard.writeText(markdown);
          setNotice('已复制最新授权的原文摘录，请核对引用后再使用。');
        } else {
          const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' });
          const url = URL.createObjectURL(blob);
          try {
            const a = document.createElement('a');
            a.href = url;
            a.download = 'cloudchunk-research-notes.md';
            document.body.append(a);
            a.click();
            a.remove();
          } finally {
            window.setTimeout(() => URL.revokeObjectURL(url), 500);
          }
          setNotice('已生成只包含重新鉴权原文的 Markdown 文件。');
        }
      } else {
        setNotice('已重新核验所选来源的当前文件权限与解析版本，可继续复制或导出。');
      }
    } catch (cause) {
      if (!controller.signal.aborted) {
        setPreview(null);
        setError(cause instanceof Error ? cause.message : '来源可能已移动、删除或重新解析，请重新检索');
      }
    } finally {
      if (inFlight.current === controller) { inFlight.current = null; setWorking(false); }
    }
  };

  if (hits.length === 0) return null;
  return <section className="kw-research" aria-label="多文档研究摘录">
    <div className="kw-section-title">
      <div><div className="kw-eyebrow"><MaterialIcon>library_books</MaterialIcon>多文档研究</div>
        <h3>整理原文摘录，不编造总结</h3>
        <p>从当前结果选择最多 12 段，每次复制或下载前都会再次验证访问权限。</p></div>
    </div>
    <div className="kw-research-list">
      {hits.map(hit => (
        <div key={keyFor(hit)} className="kw-research-row">
          <input type="checkbox" aria-label={`选择 ${hit.fileName} 的片段 ${hit.chunkIndex + 1}`} checked={selected.includes(keyFor(hit))}
            disabled={working || (!selected.includes(keyFor(hit)) && selected.length >= 12)}
            onChange={() => toggle(hit)} />
          <span className="kw-research-name">{hit.fileName}<small>片段 {hit.chunkIndex + 1} · 版本 {hit.generation}</small></span>
          <button type="button" className="kw-button" onClick={event => { event.preventDefault(); onSourceOpen(hit); }}>
            核对原文 <MaterialIcon>open_in_new</MaterialIcon>
          </button>
        </div>
      ))}
    </div>
    <div className="kw-research-actions">
      <span>已选 {selected.length}/12</span>
      <button type="button" className="kw-button" disabled={working || selected.length === 0} onClick={() => void prepare()}>
        <MaterialIcon>fact_check</MaterialIcon>{working ? '正在校验…' : '验证来源'}
      </button>
      <button type="button" className="kw-button" disabled={working || selected.length === 0} onClick={() => void prepare('copy')}>
        <MaterialIcon>content_copy</MaterialIcon>复制引用
      </button>
      <button type="button" className="kw-button" disabled={working || selected.length === 0} onClick={() => void prepare('file')}>
        <MaterialIcon>download</MaterialIcon>导出 Markdown
      </button>
    </div>
    {error && <p className="kw-error" role="alert">{error} · 已停止导出，请重新检索并确认访问权限。</p>}
    {notice && <p className="kw-notice" role="status">{notice}</p>}
    {preview && <div className="kw-research-preview">
      <strong>已核验的片段（最多显示前 4 条预览）</strong>
      {preview.evidence.slice(0,4).map((hit,i) => (
        <p key={keyFor(hit)}><b>[S{i+1}] {hit.fileName}</b> — {hit.content.slice(0,160)}{hit.content.length>160?'…':''}</p>
      ))}
      <p>这不是 AI 结论；原文版本或授权变化后，请重新验证。</p>
    </div>}
  </section>;
}
