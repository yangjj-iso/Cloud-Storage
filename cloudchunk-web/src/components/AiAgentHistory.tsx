import { useEffect, useState } from 'react';
import { knowledgeApi, type MoveProposal, type RenameProposal } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

/** Read-only per-user audit summary, never a shortcut that bypasses confirmation. */
export function AiAgentHistory() {
  const [open,setOpen]=useState(false);
  const [revision,setRevision]=useState(0);
  const [moves,setMoves]=useState<MoveProposal[]>([]);
  const [renames,setRenames]=useState<RenameProposal[]>([]);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [undoBusy,setUndoBusy]=useState<string | null>(null);
  useEffect(()=>{
    if(!open)return;
    const ctrl=new AbortController();
    setBusy(true);setError('');
    void Promise.all([
      knowledgeApi.recentMoveProposals(ctrl.signal),
      knowledgeApi.recentRenameProposals(ctrl.signal),
    ]).then(([moveRecords,renameRecords])=>{
      if(!ctrl.signal.aborted){setMoves(moveRecords);setRenames(renameRecords);}
    }).catch(e=>{
      if(!ctrl.signal.aborted)setError(e instanceof Error?e.message:'整理记录暂不可用');
    }).finally(()=>{if(!ctrl.signal.aborted)setBusy(false);});
    return ()=>ctrl.abort();
  },[open,revision]);

  const undo = async (record: MoveProposal) => {
    if(undoBusy || record.status!=='APPLIED')return;
    const question='确定尝试将「'+record.fileName+'」移回原来的文件夹？'+
      '只有执行后未发生其他修改、原目录仍有效且未超出15分钟才能撤销。';
    if(!window.confirm(question))return;
    const ctrl=new AbortController();
    setUndoBusy(record.id);setError('');
    try {
      await knowledgeApi.undoMove(record.id,true,ctrl.signal);
      setRevision(x=>x+1);
    } catch(e) {
      setError(e instanceof Error?e.message:'撤销失败：文件或原目录可能已经变化');
    } finally {
      setUndoBusy(null);
    }
  };
  return <section className="kw-agent-history" aria-label="AI 文件整理历史">
    <div className="kw-agent-history-header">
      <div><h3>AI 文件整理记录</h3>
        <p>仅当前账号 · 最近的重命名与移动建议 · 记录不能绕过操作确认</p></div>
      <button type="button" className="kw-button" aria-expanded={open}
        onClick={()=>setOpen(x=>!x)}>{open?'收起记录':'查看记录'}</button>
    </div>
    {open && <>
      <div className="kw-agent-history-actions">
        <button type="button" className="kw-button" disabled={busy}
          onClick={()=>setRevision(x=>x+1)}>
          <MaterialIcon>refresh</MaterialIcon>刷新
        </button>
      </div>
      {busy && <p role="status">正在读取当前账号的历史记录…</p>}
      {error && <p className="kw-error" role="alert">{error}</p>}
      {!busy && !error && moves.length===0 && renames.length===0 &&
        <p>目前没有可显示的 AI 整理建议。</p>}
      {!busy && !error && <div className="kw-agent-history-groups">
        <div>
          <h4>文件归类与移动</h4>
          {moves.length===0 && <p>暂无记录</p>}
          {moves.map(record=><div className="kw-agent-history-row" key={'move-'+record.id}>
            <strong>{record.fileName}</strong>
            <span>原目录 {record.sourceParentId===0?'根目录':record.sourceParentId} → {record.destinationName}</span>
            <small>{record.status==='REVERTED'?'已撤销':record.status==='APPLIED'?'已执行':'未执行（可能已过期）'} · 提议到期 {record.expiresAt}</small>
            {record.status==='APPLIED' && <button type="button" className="kw-button"
              disabled={undoBusy!==null} onClick={()=>void undo(record)}>
              {undoBusy===record.id?'正在撤销…':'尝试撤销（限执行后15分钟）'}
            </button>}
          </div>)}
        </div>
        <div>
          <h4>文件重命名</h4>
          {renames.length===0 && <p>暂无记录</p>}
          {renames.map(record=><div className="kw-agent-history-row" key={'rename-'+record.id}>
            <strong>{record.oldName}</strong>
            <span>→ {record.newName}</span>
            <small>{record.status==='APPLIED'?'已执行':'未执行（可能已过期）'} · 到期 {record.expiresAt}</small>
          </div>)}
        </div>
      </div>}
    </>}
  </section>;
}
