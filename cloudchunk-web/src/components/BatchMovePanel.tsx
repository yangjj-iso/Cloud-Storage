import { useEffect, useMemo, useRef, useState } from 'react';
import { knowledgeApi, type BatchMovePreview, type FolderChoices, type MoveProposal,
  type SearchResult, type SourceRef } from '../lib/knowledge';
import { createOperationGate, pendingPlanIds } from '../lib/workflowExperience';
import { MaterialIcon } from './ui/MaterialIcon';
import { AgentWorkflowPanel } from './AgentWorkflowPanel';

function refFor(hit: SearchResult['evidence'][number]): SourceRef {
  return { userFileId: hit.userFileId, generation: hit.generation, chunkIndex: hit.chunkIndex };
}

/** Batch PREVIEW ONLY. Every file mutation still needs individual confirmation. */
export function BatchMovePanel({ results }: { results: SearchResult }) {
  const sources = useMemo(() => results.evidence.filter((item, index, array) =>
    array.findIndex(other => other.userFileId === item.userFileId) === index).slice(0, 12), [results]);
  const [enabled, setEnabled] = useState(false);
  const [selectedFiles, setSelectedFiles] = useState<string[]>([]);
  const [folderChoices, setFolderChoices] = useState<FolderChoices | null>(null);
  const [folderRevision, setFolderRevision] = useState(0);
  const [folderLoading, setFolderLoading] = useState(false);
  const [selectedFolders, setSelectedFolders] = useState<number[]>([]);
  const [consent, setConsent] = useState(false);
  const [preview, setPreview] = useState<BatchMovePreview | null>(null);
  const [approved, setApproved] = useState<string[]>([]);
  const [applied, setApplied] = useState<string[]>([]);
  const [undone, setUndone] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState('');
  const [workflowId, setWorkflowId] = useState<string | null>(null);
  const abort = useRef<AbortController | null>(null);
  const gate = useRef(createOperationGate());

  useEffect(() => {
    const ctrl = new AbortController();
    void knowledgeApi.moveAgentCapabilities(ctrl.signal)
      .then(c => { if (!ctrl.signal.aborted) setEnabled(c.enabled); })
      .catch(() => { if (!ctrl.signal.aborted) setEnabled(false); });
    return () => { ctrl.abort(); abort.current?.abort(); };
  }, []);
  // A new search is a new authorization/generation snapshot. Never carry consent across it.
  useEffect(() => {
    abort.current?.abort(); abort.current = null;
    setSelectedFiles([]); setSelectedFolders([]); setConsent(false); setPreview(null);
    setApproved([]); setApplied([]); setUndone([]); setWorkflowId(null);
    setError(''); setUncertain(false); setBusy(false);
    return () => abort.current?.abort();
  }, [results]);
  const first = selectedFiles[0];
  useEffect(() => {
    setFolderChoices(null); setSelectedFolders([]); setConsent(false); setFolderLoading(false);
    if (!enabled || !first) return;
    const ctrl = new AbortController(); setFolderLoading(true);
    void knowledgeApi.folderChoices(first, ctrl.signal)
      .then(data => { if (!ctrl.signal.aborted) setFolderChoices(data); })
      .catch(() => { if (!ctrl.signal.aborted) setError('候选目录读取失败，请重试或检查资料权限'); })
      .finally(() => { if (!ctrl.signal.aborted) setFolderLoading(false); });
    return () => ctrl.abort();
  }, [first, enabled, folderRevision]);

  const resetPreview = () => {
    setConsent(false); setPreview(null); setApproved([]); setApplied([]); setUndone([]);
    setWorkflowId(null); setUncertain(false); setError('');
  };
  const toggleFile = (id: string, checked: boolean) => {
    if (busy) return;
    setSelectedFiles(current => !checked ? current.filter(x => x !== id)
      : current.includes(id) || current.length >= 4 ? current : [...current, id]);
    resetPreview();
  };
  const toggleFolder = (id: number, checked: boolean) => {
    if (busy) return;
    setSelectedFolders(current => !checked ? current.filter(x => x !== id)
      : current.includes(id) || current.length >= 12 ? current : [...current, id]);
    resetPreview();
  };

  const run = async (operation: (signal: AbortSignal) => Promise<void>, mutation = false) => {
    const lease = gate.current.acquire(); if (!lease) return;
    const ctrl = new AbortController(); abort.current = ctrl;
    setBusy(true); setError('');
    try { await operation(ctrl.signal); }
    catch (cause) {
      if (!ctrl.signal.aborted) {
        setError((cause instanceof Error ? cause.message : '请求未完成') + (mutation
          ? '。结果可能已写入，请到「整理记录」核对；不要直接重复执行。' : '。请检查后重试。'));
        if (mutation) setUncertain(true);
      }
    } finally {
      gate.current.release(lease);
      if (abort.current === ctrl) { abort.current = null; if (!ctrl.signal.aborted) setBusy(false); }
    }
  };
  const generate = () => {
    if (!consent || !enabled || busy || !selectedFiles.length || !selectedFolders.length) return;
    const chosen = sources.filter(s => selectedFiles.includes(s.userFileId)).map(refFor);
    if (chosen.length !== selectedFiles.length) { resetPreview(); setError('检索结果已改变，请重新选择文件'); return; }
    setConsent(false);
    void run(async signal => {
      const result = await knowledgeApi.previewBatchMove(chosen, [...selectedFolders], true, signal);
      if (!signal.aborted) setPreview(result);
    });
  };
  const apply = (item: MoveProposal) => {
    if (busy || uncertain || applied.includes(item.id) || !approved.includes(item.id)) return;
    if (!window.confirm('仅将「' + item.fileName + '」移动到「' + item.destinationName + '」？其他建议不会执行。')) return;
    void run(async signal => {
      await knowledgeApi.applyMove(item.id, true, signal);
      if (!signal.aborted) { setApplied(x => [...x, item.id]); setApproved(x => x.filter(id => id !== item.id)); }
    }, true);
  };
  const undo = (item: MoveProposal) => {
    if (busy || uncertain || undone.includes(item.id) || !applied.includes(item.id)) return;
    if (!window.confirm('尝试撤销「' + item.fileName + '」的移动？仅在15分钟内且文件未变化时允许。')) return;
    void run(async signal => {
      await knowledgeApi.undoMove(item.id, true, signal);
      if (!signal.aborted) setUndone(x => [...x, item.id]);
    }, true);
  };
  const pending = preview ? pendingPlanIds(preview, applied, undone) : [];
  const createWorkflow = () => {
    if (!preview || busy || uncertain || workflowId || !pending.length || pending.length > 4) return;
    if (!window.confirm('将 ' + pending.length + ' 条未执行建议保存为计划？不包含已执行或已撤销的项目，不会移动文件。')) return;
    void run(async signal => {
      const created = await knowledgeApi.createWorkflow(pending, signal);
      if (!signal.aborted) setWorkflowId(created.summary.id);
    }, true);
  };

  if (!enabled || sources.length === 0) return null;
  return <section className="kw-batch-organizer" aria-label="AI 批量整理预览" aria-busy={busy}>
    <div className="kw-eyebrow"><MaterialIcon>rule_folder</MaterialIcon>受控 AI 批量整理</div>
    <h3>选择文件 → 选择目录 → 预览 → 逐项确认</h3>
    <p className="kw-footnote">最多选择4份文件和12个候选目录。模型只生成建议；不自动批准或移动文件。</p>
    {!preview && <>
      <p role="status">已选 {selectedFiles.length} / 4 份文件 · {selectedFolders.length} / 12 个目录</p>
      <div className="kw-batch-choice-list" role="group" aria-label="选择参与整理的文件">
        {sources.map(source => <label key={source.userFileId}>
          <input type="checkbox" checked={selectedFiles.includes(source.userFileId)}
            disabled={busy || (!selectedFiles.includes(source.userFileId) && selectedFiles.length >= 4)}
            onChange={e => toggleFile(source.userFileId, e.target.checked)} /><span>{source.fileName}</span>
        </label>)}
      </div>
      {selectedFiles.length > 0 && <>
        <p className="kw-footnote">每个文件都会重新检查目标目录权限。</p>
        {folderLoading && <p role="status">正在读取可用目录…</p>}
        {!folderLoading && !folderChoices && <button type="button" className="kw-button" disabled={busy}
          onClick={() => { setError(''); setFolderRevision(v => v + 1); }}>重试读取目录</button>}
        {folderChoices?.folders.length === 0 && <p>没有可用目标目录。请先到云盘创建目录，再点击重新读取。</p>}
        {folderChoices && <>
          <div className="kw-batch-choice-list" role="group" aria-label="选择目标文件夹候选">
            {folderChoices.folders.map(folder => <label key={folder.id}>
              <input type="checkbox" checked={selectedFolders.includes(folder.id)}
                disabled={busy || (!selectedFolders.includes(folder.id) && selectedFolders.length >= 12)}
                onChange={e => toggleFolder(folder.id, e.target.checked)} /><span>{folder.name}（目录 ID {folder.id}）</span>
            </label>)}
          </div>
          {folderChoices.hasMore && <p className="kw-footnote">仅展示服务端返回的候选目录，不代表全部目录。</p>}
          <button type="button" className="kw-button" disabled={busy}
            onClick={() => setFolderRevision(v => v + 1)}>重新读取目录</button>
        </>}
      </>}
      <label className="kw-consent"><input type="checkbox" checked={consent}
        disabled={busy || !selectedFiles.length || !selectedFolders.length} onChange={e => setConsent(e.target.checked)} />
        <span>本次同意把已选文件的最多4个原文片段与候选目录名发送给模型，每个成功调用计入当天额度。只生成预览。</span></label>
      <button className="kw-button" type="button" onClick={generate}
        disabled={!consent || busy || !selectedFiles.length || !selectedFolders.length}>
        {busy ? '正在逐份生成建议…' : '生成批量预览（不执行）'}</button>
    </>}
    {preview && !workflowId && <div className="kw-batch-preview">
      <p role="status">共生成 {preview.proposed} 条建议；{preview.skipped} 份未生成建议。每项需独立核对和确认。</p>
      {preview.items.map((entry, index) => entry.proposal ? <div className="kw-batch-preview-item" key={entry.proposal.id}>
        <strong>{entry.proposal.fileName}</strong><span>原目录 ID {entry.proposal.sourceParentId} → {entry.proposal.destinationName}</span>
        <small>建议截止（服务端时间）：{entry.proposal.expiresAt}</small>
        {undone.includes(entry.proposal.id) ? <span>已撤销</span>
          : applied.includes(entry.proposal.id) ? <button type="button" className="kw-button" disabled={busy || uncertain}
            onClick={() => undo(entry.proposal!)}>尝试撤销本项</button>
          : <><label className="kw-consent"><input type="checkbox" checked={approved.includes(entry.proposal.id)} disabled={busy || uncertain}
            onChange={e => setApproved(v => e.target.checked ? [...new Set([...v, entry.proposal!.id])] : v.filter(id => id !== entry.proposal!.id))} />
            <span>我已核对本项来源与目标</span></label>
            <button type="button" className="kw-button kw-rename-confirm" disabled={!approved.includes(entry.proposal.id) || busy || uncertain}
              onClick={() => apply(entry.proposal!)}>批准并执行本项移动</button></>}
      </div> : <div className="kw-batch-preview-item" key={'skip-' + index}>
        <strong>文件 {entry.source.userFileId}</strong><span>无法生成建议，未执行操作</span></div>)}
      <button type="button" className="kw-button kw-rename-confirm" disabled={busy || uncertain || pending.length === 0}
        onClick={createWorkflow}><MaterialIcon>account_tree</MaterialIcon>保存未执行建议为计划（{pending.length} 项，不执行）</button>
      <button type="button" className="kw-button" disabled={busy} onClick={resetPreview}>关闭预览并重新选择</button>
    </div>}
    {workflowId && <AgentWorkflowPanel initialId={workflowId} />}
    {error && <p className="kw-error" role="alert">{error}</p>}
  </section>;
}
