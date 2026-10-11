import { useEffect, useRef, useState } from 'react';
import { knowledgeApi, type StrategyConsentReceipt, type StrategyFeedbackQuality,
  type StrategyPolicy, type StrategyRecommendation, type StrategyReview } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';
import './ai-privacy.css';

const eventLabels: Record<string,string> = {
  CONSENT_GRANTED:'已确认一次模型请求',
  RECOMMENDED:'候选推荐已生成',
  REQUEST_FAILED:'本次请求未完成',
  RECOMMENDATIONS_BLOCKED:'已关闭后续策略推荐',
  RECOMMENDATIONS_ALLOWED:'已允许后续逐次授权',
};
const reviewLabels: Record<string,string> = {
  REQUESTED:'等待独立复核', REVIEWED:'复核已完成',
  CONFIRMED:'分类已确认', FOLLOWUP_REQUIRED:'后续需要人工处理',
  INSUFFICIENT_EVIDENCE:'现有分类不足以判断',
};

/** Always user-scoped; preference changes never retroactively unsend provider input. */
export function AiPrivacyPanel() {
  const [policy,setPolicy] = useState<StrategyPolicy | null>(null);
  const [consents,setConsents] = useState<StrategyConsentReceipt[]>([]);
  const [feedback,setFeedback] = useState<StrategyRecommendation[]>([]);
  const [reviews,setReviews] = useState<StrategyReview[]>([]);
  const [quality,setQuality] = useState<StrategyFeedbackQuality | null>(null);
  const [revision,setRevision] = useState(0);
  const [busy,setBusy] = useState(false);
  const [error,setError] = useState('');
  const [notice,setNotice] = useState('');
  const active=useRef<AbortController | null>(null);

  useEffect(()=>{
    const controller=new AbortController();
    setError('');
    void Promise.allSettled([
      knowledgeApi.strategyPolicy(controller.signal),
      knowledgeApi.strategyConsentHistory(controller.signal),
      knowledgeApi.strategyRecommendationHistory(controller.signal),
      knowledgeApi.feedbackReviews(controller.signal),
      knowledgeApi.strategyFeedbackQuality(controller.signal),
    ]).then(([policyResult,historyResult,feedbackResult,reviewResult,qualityResult])=>{
      if(controller.signal.aborted)return;
      setPolicy(policyResult.status==='fulfilled'?policyResult.value:null);
      setConsents(historyResult.status==='fulfilled'?historyResult.value:[]);
      setFeedback(feedbackResult.status==='fulfilled'?feedbackResult.value:[]);
      setReviews(reviewResult.status==='fulfilled'?reviewResult.value:[]);
      setQuality(qualityResult.status==='fulfilled'?qualityResult.value:null);
      if(policyResult.status==='rejected')setError('当前模型授权设置不可用，请确认服务端升级。');
    });
    return ()=>controller.abort();
  },[revision]);
  useEffect(()=>()=>active.current?.abort(),[]);

  const changePolicy=async (allow:boolean) => {
    if(busy||!policy)return;
    if(!window.confirm(allow
      ?'重新允许未来的策略推荐请求？每一次发送正文前仍需独立勾选授权。'
      :'停止未来的 AI 整理策略推荐？已开始的模型请求和已发送给提供商的信息无法撤回，已有文件操作不会被改变。'))return;
    const ctrl=new AbortController();active.current=ctrl;setBusy(true);setError('');setNotice('');
    try {
      const next=await knowledgeApi.setStrategyPolicy(allow,ctrl.signal);
      if(!ctrl.signal.aborted){setPolicy(next);setRevision(n=>n+1);
        setNotice(allow?'已允许未来逐次授权的策略推荐。':'已关闭之后的策略推荐；已启动的请求可能仍会完成。');}
    } catch(e) {if(!ctrl.signal.aborted)setError(e instanceof Error?e.message:'无法保存设置');}
    finally{if(active.current===ctrl){active.current=null;setBusy(false);}}
  };

  const requestReview=async(id:string) => {
    if(busy || !window.confirm('为本次已有评价申请人工分类复核？仅提交固定反馈类别及复核请求，不会自动修改评价、发送正文或执行文件操作。'))return;
    const ctrl=new AbortController();active.current=ctrl;setBusy(true);setError('');setNotice('');
    try{
      await knowledgeApi.requestFeedbackReview(id,ctrl.signal);
      if(!ctrl.signal.aborted){setNotice('已提交复核请求。后续结果会在此页面显示。');setRevision(n=>n+1);}
    }catch(e){if(!ctrl.signal.aborted)setError(e instanceof Error?e.message:'复核请求失败，来源可能已失效或重复申请');}
    finally{if(active.current===ctrl){active.current=null;setBusy(false);}}
  };
  const rated=feedback.filter(item=>item.state==='ACCEPTED'||item.state==='REJECTED');
  // Review API intentionally omits recommendation IDs, so disable new requests
  // server-side on duplicates rather than guessing relationships in the browser.

  return <section className="ai-privacy">
    <div className="ai-hub-section-head">
      <div><span className="ai-hub-eyebrow">CONSENT & FEEDBACK GOVERNANCE</span>
        <h2>我的 AI 隐私控制</h2>
        <p>控制未来策略推荐的模型调用、查看授权记录并申请人工复核。设置只影响当前账号。</p></div>
      <button type="button" className="ai-hub-outline" disabled={busy}
        onClick={()=>setRevision(n=>n+1)}><MaterialIcon>refresh</MaterialIcon>刷新状态</button>
    </div>
    {error&&<p className="ai-hub-notice" role="alert">{error}</p>}
    {notice&&<p className="ai-hub-success" role="status">{notice}</p>}

    <div className="ai-privacy-top">
      <div>
        <span className="ai-hub-eyebrow">FUTURE MODEL CALLS</span>
        <h3>允许 AI 推荐整理策略</h3>
        <p>即使允许，发送任何选定资料正文时仍需当次明确勾选授权。关闭之后服务器会拒绝新的策略推荐请求。</p>
        <p className="ai-privacy-note">关闭无法召回已经发送的正文，不会停止已经开始的请求，也不控制独立的问答或语义索引授权。</p>
      </div>
      <div className="ai-privacy-switch">
        <strong>{policy===null?'状态未知':policy.recommendationsAllowed?'允许逐次授权':'禁止后续策略推荐'}</strong>
        <button type="button" className={policy?.recommendationsAllowed?'ai-hub-outline':'ai-hub-primary'}
          disabled={busy||policy===null} onClick={()=>void changePolicy(!policy?.recommendationsAllowed)}>
          <MaterialIcon>{policy?.recommendationsAllowed?'block':'verified_user'}</MaterialIcon>
          {policy?.recommendationsAllowed?'关闭后续推荐':'重新允许'}
        </button>
        {policy&&!policy.explicitChoice&&<small>尚未保存长期偏好；现有单次授权要求仍有效</small>}
      </div>
    </div>

    <div className="ai-privacy-columns">
      <div className="ai-privacy-section">
        <h3><MaterialIcon>history</MaterialIcon>最近授权事件</h3>
        <p>仅包括固定事件类型、当次候选数量和记录时间，最多显示 30 条。</p>
        {consents.length===0&&<span className="ai-privacy-muted">暂无事件，或服务暂时不可用。</span>}
        <ol>{consents.map((entry,i)=><li key={i}>
          <strong>{eventLabels[entry.event]??'受控授权事件'}</strong>
          <span>{entry.candidateCount>0?`候选 ${entry.candidateCount} 项 · `:''}{entry.recordedAt}</span>
        </li>)}</ol>
      </div>
      <div className="ai-privacy-section">
        <h3><MaterialIcon>fact_check</MaterialIcon>反馈质量与人工复核</h3>
        <p>复核仅核查当前反馈的分类，不审阅用户文件；不会改写原评价或自动训练模型。</p>
        {quality&&<div className="ai-privacy-counts">
          <span>接受 <strong>{quality.accepted}</strong></span>
          <span>拒绝 <strong>{quality.rejected}</strong></span>
          <span>待反馈 <strong>{quality.unrated}</strong></span>
        </div>}
        <h4>最近已评价的推荐</h4>
        {rated.length===0&&<span className="ai-privacy-muted">没有可申请复核的近期评价。</span>}
        {rated.map(item=><div key={item.id} className="ai-privacy-rated">
          <div><strong>{item.templateName}</strong>
            <small>{item.state==='ACCEPTED'?'已评价：有帮助':'已评价：不采纳'} · {item.createdAt}</small></div>
          <button type="button" className="ai-hub-outline" disabled={busy}
            onClick={()=>void requestReview(item.id)}>申请复核</button>
        </div>)}
        <h4>我的复核记录</h4>
        {reviews.length===0&&<span className="ai-privacy-muted">暂无复核记录。</span>}
        {reviews.map(item=><div key={item.id} className="ai-privacy-review">
          <strong>{reviewLabels[item.state]??item.state}</strong>
          <small>{item.reviewCode?(reviewLabels[item.reviewCode]??'分类结果'):item.feedbackReason} · {item.createdAt}</small>
        </div>)}
      </div>
    </div>
    <div className="ai-hub-safety"><MaterialIcon>shield</MaterialIcon><div>
      <strong>授权和评价不会改变文件</strong>
      <p>评价复核处理的是分类信息，模型提供商的数据留存仍以部署方配置和服务条款为准；原文件移动必须在整理策略页面另行审批。</p>
    </div></div>
  </section>;
}
