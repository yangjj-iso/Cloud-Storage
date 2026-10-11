import { useEffect, useRef, useState } from 'react';
import { knowledgeApi, type OrganizeTemplate, type SearchResult,
  type SourceRef, type StrategyRecommendation, type StrategyFeedbackQuality,
  type StrategyRejectReason, type StrategyConsentReceipt } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

const reasons: Array<{ id: StrategyRejectReason; label: string }> = [
  {id:'WRONG_FOLDER',label:'目标文件夹不合适'},
  {id:'WRONG_CATEGORY',label:'文件类型 / 分类不合适'},
  {id:'INSUFFICIENT_CONTEXT',label:'原文信息不足'},
  {id:'OTHER',label:'其他原因'},
];

/**
 * One consented model call for one owned evidence pointer. A suggestion and
 * rating NEVER move a file; the organization simulation remains a separate step.
 */
export function AiStrategyRecommendationPanel({ results }: { results: SearchResult }) {
  const sources = results.evidence.filter((hit,index,all) =>
    all.findIndex(item => item.userFileId === hit.userFileId) === index).slice(0,12);
  const [templates,setTemplates] = useState<OrganizeTemplate[]>([]);
  const [selectedTemplateIds,setSelectedTemplateIds] = useState<string[]>([]);
  const [selectedSource,setSelectedSource] = useState('');
  const [consent,setConsent] = useState(false);
  const [recommendation,setRecommendation] = useState<StrategyRecommendation | null>(null);
  const [history,setHistory] = useState<StrategyRecommendation[]>([]);
  const [quality,setQuality] = useState<StrategyFeedbackQuality | null>(null);
  const [consents,setConsents] = useState<StrategyConsentReceipt[]>([]);
  const [reason,setReason] = useState<StrategyRejectReason>('WRONG_FOLDER');
  const [working,setWorking] = useState(false);
  const [error,setError] = useState('');
  const [notice,setNotice] = useState('');
  const [reload,setReload] = useState(0);
  const active=useRef<AbortController | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void Promise.allSettled([
      knowledgeApi.organizationTemplates(controller.signal),
      knowledgeApi.strategyRecommendationHistory(controller.signal),
      knowledgeApi.strategyFeedbackQuality(controller.signal),
      knowledgeApi.strategyConsentHistory(controller.signal),
    ]).then(([templatesResult,historyResult,qualityResult,consentResult]) => {
      if(controller.signal.aborted)return;
      setTemplates(templatesResult.status==='fulfilled' ? templatesResult.value : []);
      setHistory(historyResult.status==='fulfilled' ? historyResult.value : []);
      setQuality(qualityResult.status==='fulfilled' ? qualityResult.value : null);
      setConsents(consentResult.status==='fulfilled' ? consentResult.value : []);
      if(templatesResult.status==='rejected')setError('整理模板暂不可用，请在「整理策略」中先保存个人规则。');
    });
    return () => controller.abort();
  }, [reload]);
  useEffect(() => () => active.current?.abort(), []);

  const currentSource=sources.find(x=>x.userFileId===selectedSource);
  const selected=templates.filter(t=>selectedTemplateIds.includes(t.id));
  const toggleTemplate=(id:string) => {
    active.current?.abort();
    setSelectedTemplateIds(previous=>previous.includes(id)
      ? previous.filter(item=>item!==id)
      : previous.length<8 ? [...previous,id] : previous);
    setRecommendation(null);setConsent(false);setError('');setNotice('');
  };
  const sourceRef=():SourceRef | null => currentSource
    ? {userFileId:currentSource.userFileId,generation:currentSource.generation,
        chunkIndex:currentSource.chunkIndex}:null;

  const suggest=async() => {
    const source=sourceRef();
    if(!source || !consent || selectedTemplateIds.length<1 || working)return;
    const ctrl=new AbortController();active.current=ctrl;
    setWorking(true);setError('');setNotice('');setRecommendation(null);setConsent(false);
    try{
      const proposal=await knowledgeApi.strategyRecommend(source,[...selectedTemplateIds],true,ctrl.signal);
      if(!ctrl.signal.aborted){setRecommendation(proposal);setReload(n=>n+1);}
    }catch(cause){if(!ctrl.signal.aborted)setError(cause instanceof Error?cause.message:'建议生成失败，请确认文件及规则仍有效');}
    finally{if(active.current===ctrl){active.current=null;setWorking(false);}}
  };
  const rate=async(decision:'ACCEPTED'|'REJECTED') => {
    if(!recommendation || recommendation.state!=='PENDING' || working)return;
    const reasonCode=decision==='ACCEPTED'?'USEFUL':reason;
    if(!window.confirm(decision==='ACCEPTED'
      ? '记录为「有帮助」？这只是反馈，不会执行移动；请到整理策略另行模拟并审批。'
      : '记录拒绝原因？反馈提交后不可修改，且不会调用模型或删除文件。'))return;
    const ctrl=new AbortController();active.current=ctrl;setWorking(true);setError('');
    try{
      const update=await knowledgeApi.strategyFeedback(recommendation.id,decision,reasonCode,ctrl.signal);
      if(!ctrl.signal.aborted){
        setRecommendation(update);
        setNotice(decision==='ACCEPTED'
          ? '已记录反馈。你仍需在整理策略页选择文件、模拟冲突并逐项确认。'
          : '已记录拒绝原因，不会修改任何文件。');
        setReload(n=>n+1);
      }
    }catch(cause){if(!ctrl.signal.aborted)setError(cause instanceof Error?cause.message:'反馈已过期或无权操作');}
    finally{if(active.current===ctrl){active.current=null;setWorking(false);}}
  };

  if(sources.length===0)return null;
  return <section className="kw-strategy-recommend" aria-label="AI 整理策略推荐">
    <div className="kw-section-title">
      <div><div className="kw-eyebrow"><MaterialIcon>psychology</MaterialIcon>AI 整理策略推荐 · M4.3</div>
        <h3>让 AI 从你保存的规则里推荐目标</h3>
        <p>单次明确授权一段原文和候选目录名。模型仅返回候选序号，不能访问文件操作工具。</p>
      </div>
    </div>
    <div className="kw-strategy-grid">
      <div>
        <label className="kw-strategy-label" htmlFor="kw-strategy-source">1. 选择一份当前搜索结果中的证据</label>
        <select id="kw-strategy-source" value={selectedSource} onChange={e=>{
          active.current?.abort();setSelectedSource(e.target.value);
          setConsent(false);setRecommendation(null);setError('');
        }}>
          <option value="">选择来源文件</option>
          {sources.map(hit=><option key={hit.userFileId} value={hit.userFileId}>
            {hit.fileName} · 片段 {hit.chunkIndex+1}
          </option>)}
        </select>
        <p className="kw-footnote">最多显示本次搜索的前 12 份不同文件；文件已被回收或解析版本变化会拒绝推荐。</p>
      </div>
      <div>
        <span className="kw-strategy-label">2. 选择最多 8 条个人模板（每个目标目录只允许一个）</span>
        <div className="kw-strategy-options">
          {templates.map(template=><label key={template.id}>
            <input type="checkbox" checked={selectedTemplateIds.includes(template.id)}
              disabled={working || (!selectedTemplateIds.includes(template.id)&&selectedTemplateIds.length>=8)}
              onChange={()=>toggleTemplate(template.id)} />
            <span><strong>{template.name}</strong><small>{template.mimeCategory} → {template.destinationName}</small></span>
          </label>)}
          {templates.length===0&&<p className="kw-footnote">还没有可用模板，请先在「整理策略」中保存目标文件夹规则。</p>}
        </div>
      </div>
    </div>
    <label className="kw-consent">
      <input type="checkbox" checked={consent} disabled={working||!currentSource||selected.length===0}
        onChange={e=>setConsent(e.target.checked)} />
      <span>本次授权将上述一段资料正文与候选目标文件夹名发送给部署方已配置的 Chat 模型（模型可能在外部）。每次调用计入今日额度。不会自动移动、归档或重命名文件。</span>
    </label>
    <div className="kw-strategy-actions">
      <button type="button" className="kw-button kw-rename-confirm" disabled={working||!consent||!currentSource||selected.length===0}
        onClick={()=>void suggest()}><MaterialIcon>auto_awesome</MaterialIcon>{working?'处理中…':'生成单次推荐（不执行）'}</button>
      <button type="button" className="kw-button" onClick={() =>
        window.dispatchEvent(new Event('cloudchunk:open-ai-strategies'))}>
        <MaterialIcon>tune</MaterialIcon>前往整理策略模拟
      </button>
    </div>
    {error&&<p className="kw-error" role="alert">{error}</p>}
    {notice&&<p className="kw-notice" role="status">{notice}</p>}
    {recommendation&&<div className="kw-strategy-result">
      <div><strong>推荐模板：{recommendation.templateName}</strong>
        <p>目标目录：{recommendation.destinationName} · 文件：{recommendation.fileName}</p>
        <small>仅生成候选建议；原建议有效期 {recommendation.proposalExpiresAt}，不代表已批准或可执行。</small>
      </div>
      {recommendation.state==='PENDING'?<div className="kw-strategy-feedback">
        <button type="button" className="kw-button" disabled={working}
          onClick={()=>void rate('ACCEPTED')}><MaterialIcon>thumb_up</MaterialIcon>有帮助（仅记录反馈）</button>
        <label>拒绝原因
          <select value={reason} disabled={working} onChange={e=>setReason(e.target.value as StrategyRejectReason)}>
            {reasons.map(r=><option key={r.id} value={r.id}>{r.label}</option>)}
          </select>
        </label>
        <button type="button" className="kw-button" disabled={working}
          onClick={()=>void rate('REJECTED')}><MaterialIcon>thumb_down</MaterialIcon>不采纳</button>
      </div>:<p className="kw-footnote">已记录 {recommendation.state==='ACCEPTED'?'接受':'拒绝'}，反馈不会触发文件操作。</p>}
    </div>}
    <div className="kw-strategy-quality">
      <strong>当前账号近 30 天反馈</strong>
      {quality?<span>接受 {quality.accepted} · 拒绝 {quality.rejected} · 待评价 {quality.unrated}
        {quality.acceptanceRatio!==null? ` · 反馈接受占比 ${(quality.acceptanceRatio*100).toFixed(1)}%`:' · 少于 5 个有效反馈，不展示比例'}</span>
        :<span>反馈统计暂不可用</span>}
      <small>仅统计主动反馈，不代表模型准确率，也不会自动用于模型训练。最近记录仅显示本人仍有权访问的文件。</small>
      {history.length>0&&<details><summary>查看最近推荐记录（最多 15 条）</summary>
        {history.slice(0,15).map(item=><p key={item.id}>{item.fileName} → {item.templateName}
          {' · '}{item.state==='ACCEPTED'?'有帮助':item.state==='REJECTED'?'未采纳':'待反馈'}</p>)}
      </details>}
    </div>
  </section>;
}
