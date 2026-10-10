import { useEffect, useRef, useState } from 'react';
import { knowledgeApi, type Evidence, type MoveProposal } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

/** Two deliberate actions: consent to a model suggestion, then human approval of the exact move. */
export function MoveAgentPanel({ source }: { source: Evidence }) {
  const [enabled,setEnabled]=useState(false);
  const [consent,setConsent]=useState(false);
  const [proposal,setProposal]=useState<MoveProposal|null>(null);
  const [done,setDone]=useState(false);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const active=useRef<AbortController|null>(null);

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
  },[source.userFileId,source.generation,source.chunkIndex]);

  const suggest=async()=>{
    if(!enabled || !consent || busy)return;
    active.current?.abort();
    const ctrl=new AbortController();active.current=ctrl;
    setConsent(false);setProposal(null);setDone(false);setBusy(true);setError('');
    try {
      const result=await knowledgeApi.suggestMove({
        userFileId:source.userFileId,generation:source.generation,chunkIndex:source.chunkIndex,
      },true,ctrl.signal);
      if(!ctrl.signal.aborted)setProposal(result);
    } catch(e){if(!ctrl.signal.aborted)setError(e instanceof Error?e.message:'无法生成归类建议');}
    finally{if(active.current===ctrl)setBusy(false);}
  };
  const apply=async()=>{
    if(!proposal || done || busy)return;
    const message='确认将「'+proposal.fileName+'」移动至「'+proposal.destinationName+
      '」文件夹？这会改变网盘目录位置，但不会修改文件内容。';
    if(!window.confirm(message))return;
    const ctrl=new AbortController();active.current=ctrl;
    setBusy(true);setError('');
    try {
      await knowledgeApi.applyMove(proposal.id,true,ctrl.signal);
      if(!ctrl.signal.aborted)setDone(true);
    } catch(e){if(!ctrl.signal.aborted)setError(e instanceof Error?e.message:'执行失败，请重新获得建议');}
    finally{if(active.current===ctrl)setBusy(false);}
  };

  if(!enabled)return null;
  return <section className="kw-rename-agent" aria-label="AI 文件归类建议">
    <div className="kw-eyebrow"><MaterialIcon>folder_open</MaterialIcon>受控 AI 文件归类</div>
    <h4>为当前文件推荐一个已有文件夹</h4>
    <p>只从你拥有的最多 12 个其他文件夹中选择。模型不会自动移动文件；建议 10 分钟内有效。</p>
    {!proposal && <>
      <label className="kw-consent">
        <input type="checkbox" checked={consent} onChange={e=>setConsent(e.target.checked)} />
        <span>同意向已配置的模型发送所选原文片段及候选文件夹名称，生成分类建议。</span>
      </label>
      <button type="button" className="kw-button kw-rename-action" disabled={!consent||busy}
        onClick={()=>void suggest()}>{busy?'正在分析…':'获取归类建议（不执行）'}</button>
    </>}
    {proposal && <div className="kw-rename-diff">
      <span>当前文件</span><strong>{proposal.fileName}</strong>
      <span>当前位置</span><strong>{proposal.sourceParentId===0?'根目录':'目录 ID '+proposal.sourceParentId}</strong>
      <span>建议的目标文件夹</span>
      <strong>{proposal.destinationName}（ID {proposal.destinationId}）</strong>
      {done?<p role="status">已确认并完成移动，请刷新文件列表查看新位置。</p>
      :<div className="kw-rename-buttons">
        <button type="button" className="kw-button" disabled={busy}
          onClick={()=>{setProposal(null);setConsent(false);}}>放弃建议</button>
        <button type="button" className="kw-button kw-rename-confirm" disabled={busy}
          onClick={()=>void apply()}>{busy?'执行中…':'确认移动到此文件夹'}</button>
      </div>}
    </div>}
    {error && <p className="kw-error" role="alert">{error}</p>}
  </section>;
}
