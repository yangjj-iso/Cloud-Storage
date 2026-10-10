import { useEffect, useRef, useState } from 'react';
import { knowledgeApi, type Evidence, type FolderChoices, type MoveProposal } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

/** User picks a small, explicit, owner-validated folder shortlist BEFORE model inference. */
export function MoveAgentPanel({ source }: { source: Evidence }) {
  const [enabled,setEnabled]=useState(false);
  const [consent,setConsent]=useState(false);
  const [choices,setChoices]=useState<FolderChoices | null>(null);
  const [selectedIds,setSelectedIds]=useState<number[]>([]);
  const [proposal,setProposal]=useState<MoveProposal | null>(null);
  const [done,setDone]=useState(false);
  const [busy,setBusy]=useState(false);
  const [loading,setLoading]=useState(false);
  const [error,setError]=useState('');
  const active=useRef<AbortController | null>(null);

  useEffect(()=>{
    const ctrl=new AbortController();
    void knowledgeApi.moveAgentCapabilities(ctrl.signal)
      .then(data=>{if(!ctrl.signal.aborted)setEnabled(data.enabled);})
      .catch(()=>{if(!ctrl.signal.aborted)setEnabled(false);});
    return ()=>{ctrl.abort();active.current?.abort();};
  },[]);
  useEffect(()=>{
    active.current?.abort();active.current=null;
    setConsent(false);setProposal(null);setDone(false);setBusy(false);setError('');
    setChoices(null);setSelectedIds([]);
    if(!enabled)return;
    setLoading(true);
    const ctrl=new AbortController();
    void knowledgeApi.folderChoices(source.userFileId,ctrl.signal)
      .then(data=>{if(!ctrl.signal.aborted)setChoices(data);})
      .catch(e=>{if(!ctrl.signal.aborted)setError(e instanceof Error?e.message:'无法加载候选文件夹');})
      .finally(()=>{if(!ctrl.signal.aborted)setLoading(false);});
    return ()=>ctrl.abort();
  },[enabled,source.userFileId,source.generation,source.chunkIndex]);

  const toggle=(id:number, checked:boolean)=>{
    setSelectedIds(previous=>{
      if(checked){
        if(previous.includes(id)||previous.length>=12)return previous;
        return [...previous,id];
      }
      return previous.filter(item=>item!==id);
    });
    setConsent(false);
  };

  const suggest=async()=>{
    if(!enabled || !consent || busy || selectedIds.length===0)return;
    active.current?.abort();
    const ctrl=new AbortController();active.current=ctrl;
    const candidateFolderIds=[...selectedIds];
    setConsent(false);setProposal(null);setDone(false);setBusy(true);setError('');
    try {
      const result=await knowledgeApi.suggestMove({
        userFileId:source.userFileId,generation:source.generation,chunkIndex:source.chunkIndex
      },candidateFolderIds,true,ctrl.signal);
      if(!ctrl.signal.aborted)setProposal(result);
    }catch(e){if(!ctrl.signal.aborted)setError(e instanceof Error?e.message:'无法生成归类建议');}
    finally{if(active.current===ctrl)setBusy(false);}
  };
  const apply=async()=>{
    if(!proposal || done || busy)return;
    const message='确认将「'+proposal.fileName+'」移动至「'+proposal.destinationName+
      '」文件夹？执行时将重新检查权限、文件版本和同名冲突。';
    if(!window.confirm(message))return;
    const ctrl=new AbortController();active.current=ctrl;
    setBusy(true);setError('');
    try {
      await knowledgeApi.applyMove(proposal.id,true,ctrl.signal);
      if(!ctrl.signal.aborted)setDone(true);
    }catch(e){if(!ctrl.signal.aborted)setError(e instanceof Error?e.message:'移动失败，请重新获得建议');}
    finally{if(active.current===ctrl)setBusy(false);}
  };
  if(!enabled)return null;
  return <section className="kw-rename-agent" aria-label="AI 文件归类建议">
    <div className="kw-eyebrow"><MaterialIcon>folder_open</MaterialIcon>受控 AI 文件归类</div>
    <h4>选择候选文件夹，让 AI 帮你推荐</h4>
    <p>先勾选最多 12 个已存在的文件夹。AI 只在你选定的范围内建议目标，不会自动修改文件。</p>
    {!proposal && <>
      {loading && <p role="status">正在检查权限并获取文件夹…</p>}
      {choices && <>
        {choices.folders.length===0 && <p>没有可供归类的其他文件夹，请先在网盘中新建文件夹。</p>}
        <div className="kw-folder-choices" role="group" aria-label="归类候选文件夹">
          {choices.folders.map(folder=><label className="kw-folder-option" key={folder.id}>
            <input type="checkbox" disabled={busy || (!selectedIds.includes(folder.id) && selectedIds.length>=12)}
              checked={selectedIds.includes(folder.id)}
              onChange={e=>toggle(folder.id,e.target.checked)} />
            <span><strong>{folder.name}</strong><small>目录 ID {folder.id} · 上级 {folder.parentId===0?'根目录':folder.parentId}</small></span>
          </label>)}
        </div>
        <p>已选择 {selectedIds.length}/12 个目标目录
          {choices.hasMore?' · 只展示前 100 个候选，更多目录暂未列出':''}</p>
      </>}
      <label className="kw-consent">
        <input type="checkbox" checked={consent} disabled={selectedIds.length===0 || busy}
          onChange={e=>setConsent(e.target.checked)} />
        <span>同意向已配置的模型发送本次来源片段和我勾选的文件夹名称，仅用于生成归类建议。</span>
      </label>
      <button type="button" className="kw-button kw-rename-action"
        disabled={!consent || !choices || selectedIds.length===0 || busy}
        onClick={()=>void suggest()}>{busy?'正在分析…':'获取建议（不执行）'}</button>
    </>}
    {proposal && <div className="kw-rename-diff">
      <span>当前文件</span><strong>{proposal.fileName}</strong>
      <span>当前位置</span><strong>{proposal.sourceParentId===0?'根目录':'目录 ID '+proposal.sourceParentId}</strong>
      <span>AI 推荐的文件夹</span><strong>{proposal.destinationName}（ID {proposal.destinationId}）</strong>
      {done?<p role="status">文件已完成移动，可刷新网盘查看新位置。</p>
      :<div className="kw-rename-buttons">
        <button type="button" className="kw-button" disabled={busy}
          onClick={()=>{setProposal(null);setConsent(false);}}>放弃建议</button>
        <button type="button" className="kw-button kw-rename-confirm" disabled={busy}
          onClick={()=>void apply()}>{busy?'正在执行…':'确认并移动文件'}</button>
      </div>}
    </div>}
    {error && <p className="kw-error" role="alert">{error}</p>}
  </section>;
}
