import { useEffect, useState } from 'react';
import { api, type DriveAuditEvent } from '../lib/api';
import { MaterialIcon } from './ui/MaterialIcon';

const operationName:Record<string,string>={
  DRIVE_MKDIR:'创建目录',DRIVE_RENAME:'重命名',DRIVE_MOVE:'移动文件',
  DRIVE_RECYCLE:'移入回收站',DRIVE_RESTORE:'恢复文件',DRIVE_PURGE:'彻底删除',
  SHARE_SAVE:'分享转存',AI_RENAME_SUGGEST:'AI 重命名建议',
  AI_RENAME_APPLY:'批准 AI 重命名',AI_MOVE_SUGGEST:'AI 归类建议',
  AI_MOVE_APPLY:'批准 AI 移动',AI_MOVE_UNDO:'撤销 AI 移动',
};

/** Read-only user-scoped metadata; operation payloads and filenames are not exposed. */
export function DriveRecentActivity() {
  const [open,setOpen]=useState(false);
  const [events,setEvents]=useState<DriveAuditEvent[]>([]);
  const [loading,setLoading]=useState(false);
  const [error,setError]=useState('');
  const [version,setVersion]=useState(0);
  useEffect(()=>{
    if(!open)return;
    let alive=true;
    setLoading(true);setError('');
    void api.driveRecentAudit().then(items=>{if(alive)setEvents(items);})
      .catch(e=>{if(alive)setError(e instanceof Error?e.message:'审计记录暂不可用');})
      .finally(()=>{if(alive)setLoading(false);});
    return ()=>{alive=false;};
  },[open,version]);
  return <section className="mt-lg rounded-xl border border-outline-variant bg-surface-container-lowest p-md" aria-label="最近文件操作记录">
    <div className="flex flex-wrap items-center justify-between gap-md">
      <div>
        <h2 className="text-title-md text-on-surface">最近文件操作</h2>
        <p className="mt-xs text-label-sm text-on-surface-variant">仅当前账号，最多30条 · 包括普通网盘及 AI 操作结果</p>
      </div>
      <div className="flex gap-sm">
        {open && <button type="button" className="rounded-lg border border-outline-variant px-md py-sm text-label-sm"
          disabled={loading} onClick={()=>setVersion(x=>x+1)}>
          <MaterialIcon className="mr-xs text-[16px]">refresh</MaterialIcon>刷新
        </button>}
        <button type="button" className="rounded-lg bg-surface-container px-md py-sm text-label-sm"
          aria-expanded={open} onClick={()=>setOpen(x=>!x)}>{open?'收起记录':'查看记录'}</button>
      </div>
    </div>
    {open && <div className="mt-md">
      {loading && <p role="status" className="text-label-sm">正在读取操作结果…</p>}
      {error && <p role="alert" className="text-label-sm text-error">{error}</p>}
      {!loading && !error && events.length===0 && <p className="text-label-sm text-on-surface-variant">暂无记录。已有旧日志可能尚未采用细分操作标签。</p>}
      {!loading && !error && events.length>0 && <ul className="space-y-xs">
        {events.map(event=><li key={event.id} className="flex flex-wrap items-center gap-md border-b border-outline-variant py-sm text-label-sm">
          <strong className="min-w-[100px] text-on-surface">{operationName[event.operation]??'文件操作'}</strong>
          <span className={event.success?'text-primary':'text-error'}>{event.success?'成功':'未成功'}</span>
          <time className="text-on-surface-variant">{event.createdAt.replace('T',' ')}</time>
          <span className="text-on-surface-variant">{event.elapsedMs} ms</span>
        </li>)}
      </ul>}
      <p className="mt-sm text-label-sm text-on-surface-variant">操作结果来自原有 API 审计，不包含请求正文；属于尽力记录，不等同于不可篡改的审计账本。</p>
    </div>}
  </section>;
}
