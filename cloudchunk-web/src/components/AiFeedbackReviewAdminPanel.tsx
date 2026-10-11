import { useEffect, useState } from 'react';
import { ApiError, getAuthHeader } from '../lib/api';
import type { StrategyReview } from '../lib/knowledge';
import type { ApiResult } from '../types';
import { MaterialIcon } from './ui/MaterialIcon';

type ReviewCode = 'CONFIRMED' | 'FOLLOWUP_REQUIRED' | 'INSUFFICIENT_EVIDENCE';

async function loadQueue(signal:AbortSignal):Promise<StrategyReview[]> {
  const response=await fetch('/api/v1/admin/strategy-feedback-reviews',
    {headers:getAuthHeader(),signal,cache:'no-store'});
  const data:ApiResult<StrategyReview[]>=await response.json();
  if(!response.ok||data.code!==0)throw new ApiError(data.code||response.status,'人工复核队列暂不可用');
  return data.data;
}

async function resolveReview(id:string,code:ReviewCode,signal:AbortSignal):Promise<void> {
  const response=await fetch('/api/v1/admin/strategy-feedback-reviews/'+encodeURIComponent(id)+'/resolve',{
    method:'POST',headers:{...getAuthHeader(),'Content-Type':'application/json'},
    body:JSON.stringify({reviewCode:code,confirmed:true}),signal,cache:'no-store',
  });
  const data:ApiResult<StrategyReview>=await response.json();
  if(!response.ok||data.code!==0)throw new ApiError(data.code||response.status,'本次复核被拒绝，可能已完成');
}

/** Category-only queue; admins cannot inspect source text or change ratings. */
export function AiFeedbackReviewAdminPanel() {
  const [cases,setCases]=useState<StrategyReview[]>([]);
  const [loading,setLoading]=useState(true);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [message,setMessage]=useState('');
  const [revision,setRevision]=useState(0);
  useEffect(()=>{
    const controller=new AbortController();setLoading(true);
    void loadQueue(controller.signal).then(setCases)
      .catch(cause=>{if(!controller.signal.aborted)setError(cause instanceof Error?cause.message:'无法读取队列');})
      .finally(()=>{if(!controller.signal.aborted)setLoading(false);});
    return ()=>controller.abort();
  },[revision]);

  const action=async(id:string,code:ReviewCode)=>{
    if(busy||!window.confirm('仅处理这个固定反馈类别的人工复核？不会修改原评价、用户文件或启动模型。'))return;
    const controller=new AbortController();setBusy(true);setError('');setMessage('');
    try{
      await resolveReview(id,code,controller.signal);
      setMessage('复核结果已保存；原始用户反馈不变。');
      setRevision(v=>v+1);
    }catch(cause){setError(cause instanceof Error?cause.message:'复核失败');}
    finally{setBusy(false);}
  };
  return <section className="mt-xl rounded-xl border border-outline-variant bg-surface-container-lowest p-lg">
    <div className="flex flex-wrap items-center justify-between gap-md">
      <div><h2 className="text-title-lg font-semibold text-on-surface">AI 反馈人工复核</h2>
        <p className="mt-xs text-label-md text-on-surface-variant">
          仅显示用户主动申请的固定反馈类别，最多 20 条；不提供原文、文件名或模型回复。
        </p></div>
      <button type="button" disabled={busy||loading} onClick={()=>setRevision(v=>v+1)}
        className="rounded-lg border border-outline-variant px-md py-sm text-label-md">
        <MaterialIcon className="mr-sm">refresh</MaterialIcon>刷新
      </button>
    </div>
    {error&&<p role="alert" className="mt-md text-error">{error}</p>}
    {message&&<p role="status" className="mt-md text-primary">{message}</p>}
    {loading&&<p className="mt-lg text-body-md text-on-surface-variant">正在读取受保护队列…</p>}
    {!loading&&cases.length===0&&<p className="mt-lg text-body-md text-on-surface-variant">目前没有待复核的分类请求。</p>}
    <div className="mt-md space-y-sm">{cases.map(item=><div key={item.id}
      className="flex flex-wrap items-center justify-between gap-md rounded-lg border border-outline-variant p-md">
      <div className="min-w-0">
        <strong className="block text-body-md">待复核：{item.feedbackReason}</strong>
        <span className="text-label-sm text-on-surface-variant">{item.createdAt} · 分类证据不足时请选择继续跟进</span>
      </div>
      <div className="flex flex-wrap gap-sm">
        <button type="button" disabled={busy} onClick={()=>void action(item.id,'CONFIRMED')}
          className="rounded-lg border border-outline-variant px-md py-sm text-label-md">确认类别</button>
        <button type="button" disabled={busy} onClick={()=>void action(item.id,'FOLLOWUP_REQUIRED')}
          className="rounded-lg border border-outline-variant px-md py-sm text-label-md">人工跟进</button>
        <button type="button" disabled={busy} onClick={()=>void action(item.id,'INSUFFICIENT_EVIDENCE')}
          className="rounded-lg border border-outline-variant px-md py-sm text-label-md">信息不足</button>
      </div>
    </div>)}</div>
  </section>;
}
