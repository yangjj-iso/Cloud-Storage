import { useEffect, useMemo, useRef, useState } from 'react';
import { knowledgeApi, type BatchMovePreview, type FolderChoices, type MoveProposal,
  type SearchResult, type SourceRef } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

function refFor(hit:SearchResult['evidence'][number]):SourceRef {
  return {userFileId:hit.userFileId,generation:hit.generation,chunkIndex:hit.chunkIndex};
}

/** Bulk AI PREVIEW ONLY. Each approved action remains separately confirmable and reversible. */
export function BatchMovePanel({results}:{results:SearchResult}) {
  const sources=useMemo(()=>results.evidence.filter((item,index,array)=>
    array.findIndex(other=>other.userFileId===item.userFileId)===index).slice(0,12),[results]);
  const [enabled,setEnabled]=useState(false);
  const [selectedFiles,setSelectedFiles]=useState<string[]>([]);
  const [folderChoices,setFolderChoices]=useState<FolderChoices|null>(null);
  const [selectedFolders,setSelectedFolders]=useState<number[]>([]);
  const [consent,setConsent]=useState(false);
  const [preview,setPreview]=useState<BatchMovePreview|null>(null);
  const [approved,setApproved]=useState<string[]>([]);
  const [applied,setApplied]=useState<string[]>([]);
  const [undone,setUndone]=useState<string[]>([]);
  const [busy,setBusy]=useState(false);
  const [busyId,setBusyId]=useState<string|null>(null);
  const [error,setError]=useState('');
  const abort=useRef<AbortController|null>(null);
  useEffect(()=>{
    const ctrl=new AbortController();
    void knowledgeApi.moveAgentCapabilities(ctrl.signal)
      .then(c=>{if(!ctrl.signal.aborted)setEnabled(c.enabled);})
      .catch(()=>{if(!ctrl.signal.aborted)setEnabled(false);});
    return ()=>{ctrl.abort();abort.current?.abort();};
  },[]);
  const first=selectedFiles[0];
  useEffect(()=>{
    setFolderChoices(null);setSelectedFolders([]);setConsent(false);
    if(!enabled || !first)return;
    const ctrl=new AbortController();
    void knowledgeApi.folderChoices(first,ctrl.signal)
      .then(data=>{if(!ctrl.signal.aborted)setFolderChoices(data);})
      .catch(()=>{if(!ctrl.signal.aborted)setError('候选文件夹获取失败，请先检查资料权限');});
    return ()=>ctrl.abort();
  },[first,enabled]);
  const toggleFile=(id:string,checked:boolean)=>{
    setSelectedFiles(current=>{
      if(!checked)return current.filter(x=>x!==id);
      return current.includes(id)||current.length>=4?current:[...current,id];
    });
    setConsent(false);setPreview(null);setApproved([]);setApplied([]);setUndone([]);
  };
  const toggleFolder=(id:number,checked:boolean)=>{
    setSelectedFolders(current=>{
      if(!checked)return current.filter(x=>x!==id);
      return current.includes(id)||current.length>=12?current:[...current,id];
    });
    setConsent(false);setPreview(null);setApproved([]);setApplied([]);setUndone([]);
  };
  const generate=async()=>{
    if(!consent || !enabled || busy || selectedFiles.length===0 || selectedFolders.length===0)return;
    const chosen=sources.filter(s=>selectedFiles.includes(s.userFileId)).map(refFor);
    const ctrl=new AbortController();abort.current=ctrl;
    setBusy(true);setConsent(false);setPreview(null);setError('');setApproved([]);setApplied([]);setUndone([]);
    try {
      const result=await knowledgeApi.previewBatchMove(chosen,[...selectedFolders],true,ctrl.signal);
      if(!ctrl.signal.aborted)setPreview(result);
    } catch(e){if(!ctrl.signal.aborted)setError(e instanceof Error?e.message:'批量预览生成失败');}
    finally{if(abort.current===ctrl)setBusy(false);}
  };
  const apply=async(item:MoveProposal)=>{
    if(busyId || applied.includes(item.id) || !approved.includes(item.id))return;
    if(!window.confirm('仅确认将「'+item.fileName+'」移动到「'+item.destinationName+
      '」。其他建议不会执行。'))return;
    const ctrl=new AbortController();
    setBusyId(item.id);setError('');
    try {
      await knowledgeApi.applyMove(item.id,true,ctrl.signal);
      setApplied(x=>[...x,item.id]);
      setApproved(x=>x.filter(id=>id!==item.id));
    }catch(e){setError(e instanceof Error?e.message:'执行失败，此项未被批准');}
    finally{setBusyId(null);}
  };
  const undo=async(item:MoveProposal)=>{
    if(busyId || undone.includes(item.id) || !applied.includes(item.id))return;
    if(!window.confirm('尝试撤销「'+item.fileName+'」的移动？操作只在15分钟内且文件未变化时允许。'))return;
    const ctrl=new AbortController();setBusyId(item.id);setError('');
    try {
      await knowledgeApi.undoMove(item.id,true,ctrl.signal);
      setUndone(x=>[...x,item.id]);
    }catch(e){setError(e instanceof Error?e.message:'无法撤销，文件可能已被修改');}
    finally{setBusyId(null);}
  };
  if(!enabled || sources.length===0)return null;
  return <section className="kw-batch-organizer" aria-label="AI 批量整理预览">
    <div className="kw-eyebrow"><MaterialIcon>rule_folder</MaterialIcon>受控 AI 批量整理</div>
    <h3>先选择文件，再逐项批准</h3>
    <p className="kw-footnote">从本页检索命中的不同文件中选最多4份。AI只生成独立建议；不自动移动、不自动批准任何建议。</p>
    {!preview && <>
      <div className="kw-batch-choice-list" role="group" aria-label="选择参与整理的文件">
        {sources.map(source=><label key={source.userFileId}>
          <input type="checkbox" checked={selectedFiles.includes(source.userFileId)}
            disabled={busy || (!selectedFiles.includes(source.userFileId) && selectedFiles.length>=4)}
            onChange={e=>toggleFile(source.userFileId,e.target.checked)} />
          <span>{source.fileName}</span>
        </label>)}
      </div>
      {selectedFiles.length>0 && <>
        <p className="kw-footnote">为本批次选择最多12个候选目标目录；每个文件都会重新检查目录权限。</p>
        {folderChoices && <div className="kw-batch-choice-list" role="group" aria-label="选择目标文件夹候选">
          {folderChoices.folders.map(folder=><label key={folder.id}>
            <input type="checkbox" checked={selectedFolders.includes(folder.id)}
              disabled={busy || (!selectedFolders.includes(folder.id) && selectedFolders.length>=12)}
              onChange={e=>toggleFolder(folder.id,e.target.checked)} />
            <span>{folder.name}（目录 ID {folder.id}）</span>
          </label>)}
        </div>}
      </>}
      <label className="kw-consent">
        <input type="checkbox" checked={consent} disabled={busy || selectedFiles.length===0 || selectedFolders.length===0}
          onChange={e=>setConsent(e.target.checked)} />
        <span>本次同意把已选文件的最多4个原文片段与候选目录名发送给模型，每个成功调用计入当天额度。只生成预览。</span>
      </label>
      <button className="kw-button" type="button" onClick={()=>void generate()}
        disabled={!consent || busy || selectedFiles.length===0 || selectedFolders.length===0}>
        {busy?'正在逐份生成建议…':'生成批量预览（不执行）'}
      </button>
    </>}
    {preview && <div className="kw-batch-preview">
      <p role="status">共生成 {preview.proposed} 条建议；{preview.skipped} 份因权限、额度或目录不符而跳过。下面每一项都需单独批准。</p>
      {preview.items.map((entry,index)=>entry.proposal ?
        <div className="kw-batch-preview-item" key={entry.proposal.id}>
          <strong>{entry.proposal.fileName}</strong>
          <span>原目录 ID {entry.proposal.sourceParentId} → {entry.proposal.destinationName}</span>
          {undone.includes(entry.proposal.id) ? <span>已撤销</span>
          : applied.includes(entry.proposal.id) ?
            <button type="button" className="kw-button" disabled={busyId!==null}
              onClick={()=>void undo(entry.proposal!)}>尝试撤销本项</button>
          : <><label className="kw-consent">
              <input type="checkbox" checked={approved.includes(entry.proposal.id)} disabled={busyId!==null}
                onChange={e=>setApproved(v=>e.target.checked?[...v,entry.proposal!.id]:v.filter(id=>id!==entry.proposal!.id))} />
              <span>我已核对本项来源与目标</span>
            </label>
            <button type="button" className="kw-button kw-rename-confirm"
              disabled={!approved.includes(entry.proposal.id)||busyId!==null}
              onClick={()=>void apply(entry.proposal!)}>批准并执行本项移动</button>
          </>}
        </div>
      : <div className="kw-batch-preview-item" key={'skip-'+index}>
          <strong>文件 {entry.source.userFileId}</strong><span>无法生成建议，未执行任何操作</span>
        </div>)}
      <button type="button" className="kw-button" disabled={busyId!==null}
        onClick={()=>{setPreview(null);setApproved([]);setConsent(false);}}>关闭预览并重新选择</button>
    </div>}
    {error && <p className="kw-error" role="alert">{error}</p>}
  </section>;
}
