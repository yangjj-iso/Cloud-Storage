import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../lib/api';
import { knowledgeApi, type OrganizeMimeCategory, type OrganizeRun,
  type OrganizeStep, type OrganizeTemplate } from '../lib/knowledge';
import type { UserFileItem } from '../types';
import { MaterialIcon } from './ui/MaterialIcon';

const categories: Array<{ value: OrganizeMimeCategory; label: string }> = [
  { value: 'ANY', label: '全部文件' }, { value: 'TEXT', label: '文本文件' },
  { value: 'PDF', label: 'PDF 文档' }, { value: 'IMAGE', label: '图片' },
  { value: 'VIDEO', label: '视频' },
];
const stateLabels: Record<OrganizeStep['state'], string> = {
  READY:'符合规则，可审批', APPROVED:'已审批，待执行',
  NOT_MATCHED:'不符合文件类型', ALREADY_THERE:'已经在目标目录',
  NAME_CONFLICT:'目标存在同名项', APPLIED:'已移动', SKIPPED:'已跳过',
};
const terminal = (state: OrganizeStep['state']) => state === 'APPLIED' || state === 'SKIPPED';

/**
 * Operator-facing deterministic organization strategies. Preview saves ONLY a
 * short-lived audit snapshot; no AI call, no background job or batch mutation.
 */
export function OrganizationStrategyPanel() {
  const [templates, setTemplates] = useState<OrganizeTemplate[]>([]);
  const [runs, setRuns] = useState<OrganizeRun[]>([]);
  const [currentFolder, setCurrentFolder] = useState(0);
  const [folderTrail, setFolderTrail] = useState<Array<{id:number;name:string}>>([]);
  const [entries, setEntries] = useState<UserFileItem[]>([]);
  const [selectedFiles, setSelectedFiles] = useState<string[]>([]);
  const [selectedTemplate, setSelectedTemplate] = useState('');
  const [targetFolder, setTargetFolder] = useState('');
  const [templateName, setTemplateName] = useState('');
  const [kind, setKind] = useState<OrganizeMimeCategory>('PDF');
  const [run, setRun] = useState<OrganizeRun | null>(null);
  const [refreshCount, setRefreshCount] = useState(0);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingFolder, setLoadingFolder] = useState(true);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const live = useRef<AbortController | null>(null);

  const reload = useCallback(() => setRefreshCount(v => v + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    void Promise.all([
      knowledgeApi.organizationTemplates(controller.signal),
      knowledgeApi.organizationRuns(controller.signal),
    ]).then(([nextTemplates,nextRuns]) => {
      if(controller.signal.aborted)return;
      setTemplates(nextTemplates);setRuns(nextRuns);
      setSelectedTemplate(current => nextTemplates.some(item => item.id === current)
        ? current : nextTemplates[0]?.id ?? '');
    }).catch(reason => {
      if(!controller.signal.aborted)setError(reason instanceof Error ? reason.message : '策略列表加载失败');
    }).finally(() => { if(!controller.signal.aborted)setLoading(false); });
    return () => controller.abort();
  }, [refreshCount]);

  useEffect(() => {
    let mounted=true;
    setLoadingFolder(true);setSelectedFiles([]);
    void api.driveList(currentFolder)
      .then(data => {if(mounted)setEntries(data);})
      .catch(reason => {if(mounted){setEntries([]);setError(reason instanceof Error ? reason.message : '目录读取失败');}})
      .finally(() => {if(mounted)setLoadingFolder(false);});
    return () => {mounted=false;};
  }, [currentFolder, refreshCount]);

  useEffect(() => () => live.current?.abort(), []);

  const execute = async (operation: (signal:AbortSignal)=>Promise<void>, success: string) => {
    if(busy)return;
    const controller=new AbortController();live.current=controller;
    setBusy(true);setError('');setMessage('');
    try {
      await operation(controller.signal);
      if(controller.signal.aborted)return;
      setMessage(success);reload();
    } catch(reason) {
      if(!controller.signal.aborted)setError(reason instanceof Error ? reason.message : '操作被服务端安全检查阻止，请刷新后重试');
    } finally {if(live.current===controller){live.current=null;setBusy(false);}}
  };

  const create = () => {
    if(!targetFolder || templateName.trim().length === 0 || templateName.trim().length > 80)return;
    if(!window.confirm('保存当前账号的整理规则？仅保存规则，不会自动移动文件，也不会调用模型。'))return;
    void execute(async signal => {
      const result=await knowledgeApi.createOrganizationTemplate(templateName.trim(),kind,targetFolder,signal);
      if(!signal.aborted){setSelectedTemplate(result.id);setTemplateName('');}
    },'规则已保存，可以选择文件进行冲突模拟。');
  };

  const archive = (item: OrganizeTemplate) => {
    if(!window.confirm('停用规则「'+item.name+'」？尚未执行的模拟计划也将不能继续执行，已移动的文件不自动恢复。'))return;
    void execute(signal=>knowledgeApi.archiveOrganizationTemplate(item.id,signal).then(()=>{}),
      '规则已停用，已执行文件不会自动恢复。');
  };

  const simulate = () => {
    if(!selectedTemplate || selectedFiles.length === 0)return;
    void execute(async signal => {
      const preview=await knowledgeApi.simulateOrganization(selectedTemplate,[...selectedFiles],signal);
      if(!signal.aborted)setRun(preview);
    },'模拟完成，所有文件仍在原位置。请选择要审核的步骤。');
  };

  const openRun = (id:string) => {
    void execute(async signal => {
      const selected=await knowledgeApi.organizationRun(id,signal);
      if(!signal.aborted)setRun(selected);
    },'已重新读取最新计划状态。');
  };

  const stepAction = (step: OrganizeStep,action:'approve'|'execute'|'skip') => {
    if(!run || busy)return;
    const explanation = action==='approve'
      ? '仅批准本项移动？文件不会改变，下一次仍需单独点击执行。'
      : action==='execute'
        ? '确定移动「'+step.fileName+'」？服务端会重新验证当前用户权限、文件版本、目标目录和同名冲突；不会执行其他步骤。'
        : '确定跳过「'+step.fileName+'」？此文件不会被移动。';
    if(!window.confirm(explanation))return;
    const id=run.id;
    void execute(async signal=>{
      const result=await knowledgeApi.organizationStep(id,step.number,action,signal);
      if(!signal.aborted)setRun(result);
    },action==='execute'?'当前步骤已完成，其他文件未自动修改。':action==='approve'
      ?'当前步骤已批准，执行前仍需单独确认。':'已跳过当前项。');
  };

  const cancel = () => {
    if(!run || !window.confirm('取消剩余模拟计划？已经执行的文件移动不会自动撤销。'))return;
    const id=run.id;
    void execute(async signal=>{
      const result=await knowledgeApi.cancelOrganizationRun(id,signal);
      if(!signal.aborted)setRun(result);
    },'计划已取消；已执行的文件不自动回滚。');
  };

  const visit = (item: UserFileItem) => {
    if(!item.isDir || !Number.isSafeInteger(item.id) || item.id<=0)return;
    setCurrentFolder(item.id);
    setFolderTrail(prev=>[...prev,{id:item.id,name:item.fileName}]);
    setRun(null);
  };

  const goRoot = () => {setCurrentFolder(0);setFolderTrail([]);setRun(null);};
  const visibleFiles=entries.filter(entry=>!entry.isDir && Number.isSafeInteger(entry.id) && entry.id>0);
  const availableDirectories=entries.filter(entry=>entry.isDir && Number.isSafeInteger(entry.id) && entry.id>0);
  const toggleFile = (id:number) => {
    const key=String(id);
    setSelectedFiles(prev=>prev.includes(key)?prev.filter(x=>x!==key):
      prev.length>=8?prev:[...prev,key]);
    setRun(null);setMessage('');
  };

  return <section className="ai-strategy" aria-label="可审核的智能整理策略">
    <div className="ai-hub-section-head">
      <div><span className="ai-hub-eyebrow">ORGANIZATION STRATEGIES</span>
        <h2>整理规则与冲突模拟</h2>
        <p>按文件类型保存常用目录规则。先模拟文件匹配、重复名称及版本风险，再由你逐项审批和执行。</p></div>
      <button type="button" className="ai-hub-outline" onClick={reload} disabled={busy||loading}>
        <MaterialIcon>refresh</MaterialIcon>刷新</button>
    </div>
    {error && <p role="alert" className="ai-hub-notice">{error}</p>}
    {message && <p role="status" className="ai-hub-success">{message}</p>}
    <div className="ai-strategy-grid">
      <div className="ai-strategy-column">
        <h3><MaterialIcon>tune</MaterialIcon> 1. 保存常用规则</h3>
        <p>仅允许选取你已有的目录。规则不会自动执行，也无需 AI 模型。</p>
        <label className="ai-strategy-label">策略名称
          <input value={templateName} maxLength={80} onChange={e=>setTemplateName(e.target.value)}
            placeholder="例如：PDF 归档到资料库" /></label>
        <label className="ai-strategy-label">文件类型
          <select value={kind} onChange={e=>setKind(e.target.value as OrganizeMimeCategory)}>
            {categories.map(k=><option key={k.value} value={k.value}>{k.label}</option>)}
          </select></label>
        <label className="ai-strategy-label">目标目录（从右侧浏览区选择）
          <select value={targetFolder} onChange={e=>setTargetFolder(e.target.value)}>
            <option value="">选择一个已有目录</option>
            {availableDirectories.map(d=><option key={d.id} value={String(d.id)}>{d.fileName}</option>)}
            {targetFolder && !availableDirectories.some(d=>String(d.id)===targetFolder) &&
              <option value={targetFolder}>之前选中的目录 #{targetFolder}</option>}
          </select></label>
        <button type="button" className="ai-hub-primary" disabled={busy||!targetFolder||!templateName.trim()}
          onClick={create}><MaterialIcon>bookmark_add</MaterialIcon>保存规则（不执行）</button>
        <h4>已保存的规则 {loading?'…':templates.length}</h4>
        <div className="ai-strategy-templates">
          {templates.map(item=><div key={item.id} className="ai-strategy-template">
            <button type="button" className={selectedTemplate===item.id?'ai-strategy-template-active':''}
              onClick={()=>{setSelectedTemplate(item.id);setRun(null);}}>
              <strong>{item.name}</strong>
              <small>{categories.find(k=>k.value===item.mimeCategory)?.label} → {item.destinationName}</small>
            </button>
            <button type="button" className="ai-strategy-remove" onClick={()=>archive(item)}
              title="停用规则" aria-label={'停用规则 '+item.name} disabled={busy}>
              <MaterialIcon>close</MaterialIcon></button>
          </div>)}
          {!loading&&templates.length===0&&<p>尚无规则。从浏览区选择目标文件夹，创建第一条规则。</p>}
        </div>
      </div>

      <div className="ai-strategy-column">
        <h3><MaterialIcon>folder_open</MaterialIcon> 2. 选择需要整理的文件</h3>
        <p>从当前目录最多选 8 份文件。切换目录会清空本次选择；目录本身不参与自动整理。</p>
        <div className="ai-strategy-breadcrumb">
          <button type="button" onClick={goRoot}>根目录</button>
          {folderTrail.map((part,i)=><span key={part.id}>/ <button type="button" onClick={()=>{
            setFolderTrail(prev=>prev.slice(0,i+1));setCurrentFolder(part.id);setRun(null);
          }}>{part.name}</button></span>)}
        </div>
        <div className="ai-strategy-files">
          {loadingFolder&&<p>正在读取当前账号目录…</p>}
          {!loadingFolder&&entries.length===0&&<p>当前目录无文件。可返回根目录或先上传文件。</p>}
          {entries.map(item=><div key={item.id} className="ai-strategy-file">
            {item.isDir ? <>
              <MaterialIcon>folder</MaterialIcon><strong>{item.fileName}</strong>
              <button type="button" onClick={()=>visit(item)} disabled={!Number.isSafeInteger(item.id)}
                className="ai-hub-outline">进入 <MaterialIcon>chevron_right</MaterialIcon></button>
            </>: <>
              <input type="checkbox" checked={selectedFiles.includes(String(item.id))}
                aria-label={'选择文件 '+item.fileName}
                disabled={busy||!Number.isSafeInteger(item.id) ||
                  (!selectedFiles.includes(String(item.id))&&selectedFiles.length>=8)}
                onChange={()=>toggleFile(item.id)} />
              <MaterialIcon>description</MaterialIcon><strong title={item.fileName}>{item.fileName}</strong>
            </>}
          </div>)}
        </div>
        <div className="ai-strategy-actions">
          <span>已选 {selectedFiles.length} / 8 份 · 本目录可选 {visibleFiles.length} 份</span>
          <button type="button" className="ai-hub-primary" disabled={busy||!selectedTemplate||selectedFiles.length===0}
            onClick={simulate}><MaterialIcon>rule</MaterialIcon>模拟冲突与影响</button>
        </div>
        <p className="ai-strategy-disclaimer">大整数文件 ID 不会作为 JavaScript 数字提交；若浏览器端无法安全表示某目录项，请使用最新 API 进行服务端鉴权处理。</p>
      </div>
    </div>

    <div className="ai-strategy-results">
      <div className="ai-hub-section-head"><div><h3>3. 审核执行结果</h3>
        <p>模拟最多有效 10 分钟；每次执行都重新检查快照和权限。历史仅显示当前账号最近 12 条。</p></div></div>
      <div className="ai-strategy-history">
        {runs.map(saved=><button type="button" key={saved.id} onClick={()=>openRun(saved.id)}
          disabled={busy} className={run?.id===saved.id?'ai-strategy-history-selected':''}>
          {saved.name} · {saved.status} · {saved.steps.length} 项
        </button>)}
      </div>
      {run ? <>
        <div className="ai-strategy-summary">
          <strong>{run.name}</strong>
          <span>目标：{run.destinationName} · 类型：{run.mimeCategory}</span>
          <span>匹配 {run.matched} · 模拟阻断 {run.blocked} · 截止 {run.expiresAt}</span>
          <span>状态：{run.status}</span>
          {run.status==='ACTIVE'&&<button type="button" className="ai-hub-outline" disabled={busy}
            onClick={cancel}>取消剩余计划</button>}
        </div>
        <div className="ai-strategy-steps">{run.steps.map((step,index)=>{
          const preceding=run.steps.slice(0,index).every(s=>terminal(s.state));
          const allowed=run.status==='ACTIVE'&&preceding;
          return <article key={step.number}>
            <span className="ai-strategy-step-number">{step.number}</span>
            <div><strong>{step.fileName}</strong><p>{stateLabels[step.state]}</p>
              <small>当前模拟状态不代表执行时仍符合条件。</small></div>
            <div className="ai-strategy-step-actions">
              {allowed&&step.state==='READY'&&<button type="button" className="ai-hub-primary"
                disabled={busy} onClick={()=>stepAction(step,'approve')}>审批本项</button>}
              {allowed&&step.state==='APPROVED'&&<button type="button" className="ai-hub-primary"
                disabled={busy} onClick={()=>stepAction(step,'execute')}>确认执行</button>}
              {allowed&&!terminal(step.state)&&<button type="button" className="ai-hub-outline"
                disabled={busy} onClick={()=>stepAction(step,'skip')}>跳过</button>}
              {!preceding&&!terminal(step.state)&&<span>上一项未完成</span>}
            </div>
          </article>;
        })}</div>
        <details className="ai-workflows-audit"><summary>查看操作审计 · {run.events.length} 条</summary>
          {run.events.map((e,i)=><div key={i}>
            <span>{e.number===0?'计划':'步骤 '+e.number} · {e.action}</span><time>{e.at}</time>
          </div>)}</details>
        <p className="ai-strategy-disclaimer">执行只移动当前确认的一份文件；模板不提供自动批量应用或无条件撤销。若需要恢复已移动文件，请使用原有网盘操作并核验路径与权限。</p>
      </>:<p className="ai-strategy-disclaimer">先选择模板和文件生成冲突模拟，或点击上方历史计划查看。</p>}
    </div>
  </section>;
}
