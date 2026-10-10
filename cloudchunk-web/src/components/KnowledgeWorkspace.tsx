import { useEffect, useRef, useState, type FormEvent } from 'react';
import { knowledgeApi, statusLabels, type BlockPage, type KnowledgeDocument,
  type Library, type SearchResult } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';
import './knowledge-workspace.css';

type Selection = { userFileId: string; fileName: string; generation: number };
const readable = (d: KnowledgeDocument) => d.status === 'READY' || d.status === 'PARTIAL';
const errorMessage = (e: unknown) => e instanceof Error ? e.message : '请求失败，请重试';

export function KnowledgeWorkspace() {
  const [page, setPage] = useState(1);
  const [revision, setRevision] = useState(0);
  const [library, setLibrary] = useState<Library | null>(null);
  const [libraryError, setLibraryError] = useState('');
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [searched, setSearched] = useState('');
  const [results, setResults] = useState<SearchResult | null>(null);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState('');
  const [selection, setSelection] = useState<Selection | null>(null);
  const [indexing, setIndexing] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const searchRequest = useRef<AbortController | null>(null);
  const indexRequest = useRef<AbortController | null>(null);
  const searchInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let disposed = false;
    let request: AbortController | null = null;
    const load = async () => {
      request?.abort();
      const current = new AbortController();
      request = current;
      const timeout = window.setTimeout(() => current.abort(), 15000);
      try {
        const data = await knowledgeApi.library(page, current.signal);
        if (!disposed && !current.signal.aborted) { setLibrary(data); setLibraryError(''); }
      } catch (error) {
        if (!disposed && request === current) setLibraryError(current.signal.aborted ? '资料列表加载超时，请刷新重试' : errorMessage(error));
      } finally { window.clearTimeout(timeout); if (!disposed) setLoading(false); }
    };
    setLoading(true); setLibrary(null); setLibraryError('');
    void load();
    const timer = window.setInterval(() => { if (!document.hidden) void load(); }, 10000);
    return () => { disposed = true; request?.abort(); window.clearInterval(timer); };
  }, [page, revision]);
  useEffect(() => () => { searchRequest.current?.abort(); indexRequest.current?.abort(); }, []);

  const search = async (event: FormEvent) => {
    event.preventDefault();
    const value = query.trim();
    if (value.length < 2 || value.length > 100) { setSearchError('请输入 2 到 100 个字符的关键词'); return; }
    searchRequest.current?.abort();
    const current = new AbortController(); searchRequest.current = current;
    setSearching(true); setSearchError(''); setResults(null); setSelection(null); setSearched(value);
    const timer = window.setTimeout(() => current.abort(), 15000);
    try {
      const data = await knowledgeApi.search(value, current.signal);
      if (!current.signal.aborted) setResults(data);
    } catch (error) {
      if (searchRequest.current === current) setSearchError(current.signal.aborted ? '搜索超时，请缩短关键词后重试' : errorMessage(error));
    } finally {
      window.clearTimeout(timer);
      if (searchRequest.current === current) setSearching(false);
    }
  };
  const clearSearch = () => {
    searchRequest.current?.abort(); searchRequest.current = null;
    setQuery(''); setSearched(''); setResults(null); setSearching(false); setSearchError(''); setSelection(null);
    searchInput.current?.focus();
  };
  const index = async (doc: KnowledgeDocument) => {
    const current = new AbortController(); indexRequest.current = current;
    setIndexing(doc.userFileId); setNotice('');
    const timer = window.setTimeout(() => current.abort(), 15000);
    try {
      await knowledgeApi.index(doc.userFileId, current.signal);
      if (!current.signal.aborted) {
        setNotice('已提交索引请求。排队任务不会重复创建；失败任务有一分钟重试冷却。');
        setRevision(v => v + 1);
      }
    } catch (error) { if (!current.signal.aborted) setNotice(errorMessage(error)); }
    finally { window.clearTimeout(timer); if (indexRequest.current === current) {
      setIndexing(null);
      if (current.signal.aborted) setNotice('请求超时，可刷新状态后重试');
    } }
  };

  return (
    <section className="kw" aria-label="资料工作台">
      <div className="kw-intro">
        <div className="kw-eyebrow"><MaterialIcon>auto_stories</MaterialIcon>内容理解 · 第一阶段</div>
        <h2>从文件中，找到依据。</h2>
        <p>检索文档正文，查看来源片段。资料仅在当前部署中处理，不发送到外部模型。</p>
        <form className="kw-search" onSubmit={search}>
          <MaterialIcon>search</MaterialIcon>
          <label className="sr-only" htmlFor="knowledge-query">搜索已解析文档的正文关键词</label>
          <input ref={searchInput} id="knowledge-query" value={query} onChange={e => setQuery(e.target.value)}
            maxLength={100} placeholder="输入正文中的关键词，例如：断点续传" autoComplete="off" />
          <button type="submit" disabled={searching || query.trim().length < 2}>{searching ? '检索中…' : '检索正文'}<MaterialIcon>arrow_forward</MaterialIcon></button>
        </form>
        <div className="kw-hints"><span><MaterialIcon>lock</MaterialIcon>仅检索你当前可访问的文件</span><span>关键词匹配 · 非语义搜索或 AI 回答</span></div>
      </div>
      <div className={`kw-layout ${selection ? 'kw-with-evidence' : ''}`}>
        <div className="kw-main">
          <div className="kw-section-title">
            <div><h3>{searched ? '正文检索结果' : '我的文档资料'}</h3><p>{searched ? `关键词：“${searched}”` : '支持 TXT、Markdown、文本型 PDF 和 DOCX'}</p></div>
            {searched ? <button className="kw-button" onClick={clearSearch}>返回资料</button>
              : <button className="kw-button" onClick={() => setRevision(v => v + 1)} disabled={loading}><MaterialIcon>refresh</MaterialIcon>刷新</button>}
          </div>
          <p className="kw-notice" role="status">{notice}</p>
          {(searchError || (!searched && libraryError)) && <div className="kw-error" role="alert">{searchError || libraryError}</div>}
          {searched ? <>
            {searching && <div className="kw-empty" role="status">正在检查权限并检索正文…</div>}
            {results && results.evidence.length === 0 && <div className="kw-empty"><MaterialIcon>manage_search</MaterialIcon><h4>没有找到匹配的已索引片段</h4><p>试试文档中的精确短语。未索引、解析失败和未提取到的部分不在搜索范围内。</p></div>}
            {results && results.evidence.length > 0 && <div className="kw-results">
              {results.evidence.map(hit => <button className="kw-hit" key={`${hit.userFileId}-${hit.generation}-${hit.chunkIndex}`} onClick={() => setSelection(hit)}>
                <span className="kw-hit-title"><MaterialIcon>description</MaterialIcon>{hit.fileName}<MaterialIcon>north_east</MaterialIcon></span>
                <span className="kw-hit-content">{hit.content}</span>
                <span className="kw-hit-meta">解析版本 {hit.generation} · 片段 {hit.chunkIndex + 1}{hit.status === 'PARTIAL' ? ' · 文档仅部分可检索' : ''}</span>
              </button>)}
              {results.hasMore && <p className="kw-footnote">当前显示前 20 个匹配片段，还有更多结果。请使用更具体的关键词；此结果不代表全量统计。</p>}
            </div>}
          </> : <>
            {loading ? <div className="kw-empty" role="status">正在加载资料…</div> : library && library.documents.length === 0 ?
              <div className="kw-empty"><MaterialIcon>library_books</MaterialIcon><h4>{page === 1 ? '从第一份文档开始' : '这一页暂无资料'}</h4><p>{page === 1 ? '上传支持的文档后会自动解析。已有文档可点击“建立索引”；文件必须先完成完整性校验。' : '资料可能已移动或删除，请返回上一页。'}</p></div> :
              <div className="kw-documents">{library?.documents.map(doc => <article className="kw-document" key={doc.userFileId}>
                <div className="kw-file-icon"><MaterialIcon>description</MaterialIcon></div>
                <div className="kw-document-body"><h4 title={doc.fileName}>{doc.fileName}</h4>
                  <p>{doc.contentChars > 0 ? `${doc.contentChars.toLocaleString()} 字符 · ${doc.chunkCount} 个片段` : '原文件的上传与下载不受解析状态影响'}</p>
                  {doc.reason && <p className="kw-reason">{doc.reason}</p>}
                </div>
                <span className={`kw-state kw-state-${doc.status.toLowerCase()}`}>{statusLabels[doc.status] ?? doc.status}</span>
                {readable(doc) ? <button className="kw-button" aria-label={`查看 ${doc.fileName} 的来源片段`} onClick={() => setSelection(doc)}>查看正文<MaterialIcon>chevron_right</MaterialIcon></button>
                  : (doc.status === 'NOT_INDEXED' || doc.status === 'FAILED') ? <button className="kw-button" disabled={indexing !== null} onClick={() => void index(doc)}>{indexing === doc.userFileId ? '提交中…' : doc.status === 'FAILED' ? '重试' : '建立索引'}</button> : null}
              </article>)}</div>}
            <div className="kw-pagination"><span>第 {page} 页 · 每页最多 20 份资料</span><div>
              <button className="kw-button" disabled={page <= 1 || loading} onClick={() => { setPage(p => p - 1); setSelection(null); }}>上一页</button>
              <button className="kw-button" disabled={!library?.hasMore || loading} onClick={() => { setPage(p => p + 1); setSelection(null); }}>下一页</button>
            </div></div>
          </>}
          <div className="kw-boundary"><MaterialIcon>info</MaterialIcon><p>当前提供文档正文索引，不生成摘要或答案。每份文件上限 20 MB，最多提取 262144 字符；扫描件 OCR、图片和内嵌附件不在本阶段范围内。</p></div>
        </div>
        {selection && <EvidencePanel key={`${selection.userFileId}-${selection.generation}`} selection={selection} onClose={() => { setSelection(null); searchInput.current?.focus(); }} />}
      </div>
    </section>
  );
}

function EvidencePanel({ selection, onClose }: { selection: Selection; onClose: () => void }) {
  const [page, setPage] = useState(1);
  const [data, setData] = useState<BlockPage | null>(null);
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus(); }, []);
  useEffect(() => {
    let disposed = false;
    const request = new AbortController();
    const timeout = window.setTimeout(() => request.abort(), 15000);
    setData(null); setError('');
    void knowledgeApi.blocks(selection.userFileId, selection.generation, page, request.signal)
      .then(result => { if (!disposed && !request.signal.aborted) setData(result); })
      .catch(e => { if (!disposed) setError(request.signal.aborted ? '正文加载超时' : errorMessage(e)); })
      .finally(() => window.clearTimeout(timeout));
    return () => { disposed = true; request.abort(); window.clearTimeout(timeout); };
  }, [selection.userFileId, selection.generation, page, refresh]);
  return <aside className="kw-evidence" aria-label="来源证据">
    <div className="kw-section-title"><div><div className="kw-eyebrow">来源证据</div><h3 ref={heading} tabIndex={-1}>{selection.fileName}</h3></div><button className="kw-icon-button" aria-label="关闭来源证据" onClick={onClose}><MaterialIcon>close</MaterialIcon></button></div>
    <p className="kw-footnote">版本 {selection.generation} · 以下位置为提取文本的字符偏移，不是原文页码。</p>
    {error && <div className="kw-error" role="alert">{error}<button className="kw-button" onClick={() => setRefresh(v => v + 1)}>重试</button></div>}
    {!data && !error && <p role="status">正在重新校验权限并读取正文…</p>}
    {data?.blocks.length === 0 && <p className="kw-footnote">当前版本没有可显示片段。资料可能已重新处理，请刷新资料列表；这不表示原文件没有内容。</p>}
    {data?.blocks.map(block => <section className="kw-block" key={block.chunkIndex}><h4>片段 {block.chunkIndex + 1}<span>字符 {block.startOffset}–{block.endOffset}</span></h4><p>{block.content}</p></section>)}
    <div className="kw-pagination"><button className="kw-button" disabled={page <= 1 || !data} onClick={() => setPage(p => p - 1)}>上一页</button><button className="kw-button" disabled={!data?.hasMore} onClick={() => setPage(p => p + 1)}>下一页</button></div>
  </aside>;
}
