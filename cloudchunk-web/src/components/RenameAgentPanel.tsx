import { useEffect, useRef, useState } from 'react';
import { knowledgeApi, type Evidence, type RenameProposal } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

/** No automatic filesystem tools. User confirms each exact server-held proposal. */
export function RenameAgentPanel({ source }: { source: Evidence }) {
  const [enabled,setEnabled]=useState(false);
  const [consent,setConsent]=useState(false);
  const [proposal,setProposal]=useState<RenameProposal | null>(null);
  const [done,setDone]=useState(false);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const active=useRef<AbortController | null>(null);

  useEffect(()=>{
    const ctrl=new AbortController();
    void knowledgeApi.renameAgentCapabilities(ctrl.signal)
      .then(data=>{if(!ctrl.signal.aborted)setEnabled(data.enabled);})
      .catch(()=>{if(!ctrl.signal.aborted)setEnabled(false);});
    return ()=>{ctrl.abort();active.current?.abort();};
  },[]);
  useEffect(()=>{
    active.current?.abort();
    setConsent(false);setProposal(null);setDone(false);setBusy(false);setError('');
  },[source.userFileId,source.generation,source.chunkIndex]);

  const suggest=async()=>{
    if(!enabled || !consent)return;
    active.current?.abort();
    const ctrl=new AbortController();active.current=ctrl;
    setConsent(false);setProposal(null);setDone(false);setBusy(true);setError('');
    try {
      const result=await knowledgeApi.suggestRename({
        userFileId:source.userFileId,generation:source.generation,chunkIndex:source.chunkIndex
      },true,ctrl.signal);
      if(!ctrl.signal.aborted)setProposal(result);
    } catch(e){if(!ctrl.signal.aborted)setError(e instanceof Error?e.message:'生成建议失败');}
    finally {if(active.current===ctrl)setBusy(false);}
  };
  const apply=async()=>{
    if(!proposal || done || busy)return;
    const message='确定将「'+proposal.oldName+'」重命名为「'+proposal.newName+'」？此操作会修改网盘文件名。';
    if(!window.confirm(message))return;
    const ctrl=new AbortController();active.current=ctrl;
    setBusy(true);setError('');
    try {
      await knowledgeApi.applyRename(proposal.id,true,ctrl.signal);
      if(!ctrl.signal.aborted)setDone(true);
    } catch(e){if(!ctrl.signal.aborted)setError(e instanceof Error?e.message:'执行失败，请重新获取建议');}
    finally{if(active.current===ctrl)setBusy(false);}
  };
  if(!enabled)return null;
  return <section className="kw-rename-agent" aria-label="AI 文件命名建议">
    <div className="kw-eyebrow"><MaterialIcon>drive_file_rename_outline</MaterialIcon>受控 AI 文件操作</div>
    <h4>根据片段提出重命名建议</h4>
    <p>模型只提供一个建议，不会自动修改文件；建议有效期 10 分钟。</p>
    {!proposal && <><label className="kw-consent">
      <input type="checkbox" checked={consent} onChange={e=>setConsent(e.target.checked)} />
      <span>同意将此来源片段发送给已配置的模型服务，以生成文件名建议。</span>
    </label>
    <button className="kw-button kw-rename-action" type="button" disabled={busy || !consent} onClick={()=>void suggest()}>
      {busy?'正在生成建议…':'获取建议（不执行）'}
    </button></>}
    {proposal && <div className="kw-rename-diff">
      <span>当前文件名</span><strong>{proposal.oldName}</strong>
      <span>建议文件名</span><strong>{proposal.newName}</strong>
      {done?<p role="status">已执行重命名。文件列表刷新后会显示新名称。</p>
      :<div className="kw-rename-buttons">
        <button className="kw-button" type="button" disabled={busy} onClick={()=>{setProposal(null);setConsent(false);}}>放弃</button>
        <button className="kw-button kw-rename-confirm" type="button" disabled={busy} onClick={()=>void apply()}>
          {busy?'正在确认…':'确认并执行重命名'}
        </button>
      </div>}
    </div>}
    {error && <p className="kw-error" role="alert">{error}</p>}
  </section>;
}
