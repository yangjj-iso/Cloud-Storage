import { useEffect, useRef, useState } from 'react';
import { knowledgeApi, type AgentWorkflow, type AgentWorkflowStep, type AgentWorkflowSummary } from '../lib/knowledge';
import { createOperationGate, isFinishedStep, workflowProgress } from '../lib/workflowExperience';
import { MaterialIcon } from './ui/MaterialIcon';
import './workflow-experience.css';

const stateLabels: Record<AgentWorkflowStep['state'], string> = {
  PENDING: '待审核', APPROVED: '已批准，待执行', APPLIED: '已执行',
  SKIPPED: '已跳过', REVERTED: '已条件撤销',
};
const actionLabels: Record<string, string> = {
  CREATED: '创建执行计划', APPROVED: '批准当前步骤', APPLIED: '执行当前步骤',
  SKIPPED: '跳过当前步骤', REVERTED: '条件撤销', CANCELLED: '取消剩余计划',
};
type Action = 'approve' | 'skip' | 'execute' | 'undo' | 'cancel';

/** Durable user-owned plan. No auto-advance, mutation retry or client-side authorization. */
export function AgentWorkflowPanel({ initialId }: { initialId?: string }) {
  const [plans, setPlans] = useState<AgentWorkflowSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(initialId ?? null);
  const [data, setData] = useState<AgentWorkflow | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);
  const active = useRef<AbortController | null>(null);
  const gate = useRef(createOperationGate());

  useEffect(() => { if (initialId) setSelectedId(initialId); }, [initialId]);
  useEffect(() => {
    const request = new AbortController();
    setLoading(true);
    void knowledgeApi.recentWorkflows(request.signal)
      .then(items => { if (!request.signal.aborted) setPlans(items); })
      .catch(cause => { if (!request.signal.aborted) setError(cause instanceof Error ? cause.message : '无法读取计划列表'); })
      .finally(() => { if (!request.signal.aborted) setLoading(false); });
    return () => request.abort();
  }, [revision]);

  useEffect(() => {
    const request = new AbortController();
    if (!selectedId) { setData(null); setDetailLoading(false); return () => request.abort(); }
    setDetailLoading(true); setError('');
    // Preserve the current plan during refresh. Never clear a successful action receipt.
    setData(current => current?.summary.id === selectedId ? current : null);
    void knowledgeApi.workflow(selectedId, request.signal)
      .then(result => {
        if (!request.signal.aborted) { setData(result); setNeedsRefresh(false); }
      })
      .catch(cause => {
        if (!request.signal.aborted) {
          setData(null); setNeedsRefresh(true);
          setError(cause instanceof Error ? cause.message : '计划已失效或无权查看');
        }
      })
      .finally(() => { if (!request.signal.aborted) setDetailLoading(false); });
    return () => request.abort();
  }, [selectedId, revision]);
  useEffect(() => () => active.current?.abort(), []);

  const operate = async (stepNo: number | null, action: Action) => {
    if (!data || busy || detailLoading || needsRefresh) return;
    const lease = gate.current.acquire();
    if (!lease) return;
    const step = data.steps.find(item => item.stepNo === stepNo);
    const subject = step ? `「${step.fileName}」 → 「${step.destinationName}」\n` : '';
    const prompt: Record<Action, string> = {
      approve: '仅批准本项？批准不会移动文件，执行还需再次确认。',
      skip: '确定跳过本项？文件不会改变，下一项才会解锁。',
      execute: '确认执行本项移动？服务端会重新检查权限、同名冲突和版本，不会执行其他步骤。',
      undo: '尝试条件撤销本项？仅在原有 15 分钟窗口内且文件及目录未被后续修改时允许。',
      cancel: '取消剩余计划？已执行移动不会自动撤回，仍需逐项条件撤销。',
    };
    if (!window.confirm(subject + prompt[action])) { gate.current.release(lease); return; }
    const ctrl = new AbortController(); active.current = ctrl;
    setBusy(true); setError(''); setNotice('');
    try {
      const next = action === 'cancel'
        ? await knowledgeApi.cancelWorkflow(data.summary.id, ctrl.signal)
        : await knowledgeApi.workflowAction(data.summary.id, stepNo!, action, ctrl.signal);
      if (ctrl.signal.aborted) return;
      setData(next);
      setPlans(items => [next.summary, ...items.filter(item => item.id !== next.summary.id)].slice(0, 20));
      setNotice(action === 'execute' ? '仅当前步骤执行成功；后续步骤仍需要独立批准。'
        : action === 'undo' ? '撤销条件已通过，当前文件位置恢复。'
        : action === 'approve' ? '当前步骤已批准；仍需单独点击「执行本项移动」。'
        : action === 'cancel' ? '已取消后续计划，已执行的文件不会自动移动。' : '本项已跳过。');
    } catch (cause) {
      if (!ctrl.signal.aborted) {
        setNeedsRefresh(true);
        setError((cause instanceof Error ? cause.message : '请求未完成') + '。请先刷新计划核对服务端状态，再决定下一步；此处不会自动重试。');
      }
    } finally {
      gate.current.release(lease);
      if (active.current === ctrl) { active.current = null; if (!ctrl.signal.aborted) setBusy(false); }
    }
  };
  const progress = data ? workflowProgress(data) : null;
  const blocked = busy || detailLoading || needsRefresh;

  return <section className="ai-workflows" aria-label="受控 AI Agent 工作流" aria-busy={busy || detailLoading}>
    <div className="ai-hub-section-head">
      <div><span className="ai-hub-eyebrow">REVIEWED WORKFLOWS</span><h2>多步骤整理计划</h2>
        <p>预览 → 审核 → 单项执行 → 核对记录。批准、取消计划都不会自动移动文件。</p></div>
      <button type="button" className="ai-hub-outline" disabled={busy || loading || detailLoading}
        onClick={() => setRevision(n => n + 1)}><MaterialIcon>refresh</MaterialIcon>刷新计划</button>
    </div>
    {error && <p role="alert" className="ai-hub-notice">{error}</p>}
    {notice && <p role="status" className="ai-hub-success">{notice}</p>}
    {busy && <p role="status" className="ai-workflows-muted">正在核对并提交当前动作，请勿重复操作。离开页面不代表服务端动作已撤销。</p>}
    <div className="ai-workflows-layout">
      <aside className="ai-workflows-list" aria-label="最近的工作流">
        <h3>最近 20 个计划</h3>
        {loading && <p className="ai-workflows-muted">正在加载记录…</p>}
        {!loading && plans.length === 0 && <p className="ai-workflows-muted">还没有计划。前往「搜索与问答」，选择检索结果，生成批量移动预览，再保存执行计划。</p>}
        {plans.map(plan => <button key={plan.id} type="button" aria-pressed={selectedId === plan.id}
          className={selectedId === plan.id ? 'ai-workflows-selected' : ''}
          onClick={() => { setSelectedId(plan.id); setNotice(''); setError(''); }} disabled={busy || detailLoading}>
          <span className="ai-workflows-list-title"><MaterialIcon>account_tree</MaterialIcon>
            {plan.total} 步 · {plan.status === 'ACTIVE' ? '进行中' : plan.status === 'COMPLETED' ? '已完成' : '已取消'}
          </span><small>{plan.applied} 已执行 · {plan.pending} 未完成 · {plan.createdAt}</small>
        </button>)}
      </aside>
      <div className="ai-workflows-details">
        {!selectedId && <div className="ai-workflows-empty"><MaterialIcon>assignment</MaterialIcon>选择一个计划，继续上次的审核。</div>}
        {detailLoading && <p role="status">正在按当前账户权限核对计划…</p>}
        {data && progress && <>
          <div className="ai-workflows-heading">
            <div><h3>执行计划 · {data.summary.total} 步</h3>
              <p>{data.summary.applied} 已执行 · {data.summary.pending} 待处理</p></div>
            {data.summary.status === 'ACTIVE' && data.summary.pending > 0 &&
              <button type="button" className="ai-hub-outline" disabled={blocked}
                onClick={() => void operate(null, 'cancel')}>取消剩余计划</button>}
          </div>
          <div className="ai-workflow-progress">
            <label>处理进度：{progress.completed} / {progress.total}
              <progress aria-label="整理计划处理进度" max={Math.max(progress.total, 1)} value={progress.completed} />
            </label><p>{progress.instruction}</p>
            <small>已处理包含已执行、已跳过和已撤销，不等同于已移动文件数。</small>
          </div>
          <ol className="ai-workflow-steps">
            {data.steps.map(step => {
              const current = progress.next?.stepNo === step.stepNo;
              return <li key={step.moveProposalId} aria-current={current ? 'step' : undefined}>
                <div className="ai-workflow-marker">{step.stepNo}</div>
                <div className="ai-workflow-step">
                  <div className="ai-workflow-step-top"><strong title={step.fileName}>{step.fileName}</strong>
                    <span className={`ai-workflow-state ai-workflow-state-${step.state.toLowerCase()}`}>{stateLabels[step.state]}</span></div>
                  <p>拟移动至：<b>{step.destinationName}</b></p>
                  <small>建议截止（服务端时间）：{step.expiresAt} · 执行时重新校验授权和目录状态</small>
                  {data.summary.status === 'ACTIVE' && !current && !isFinishedStep(step.state) &&
                    <p className="ai-workflows-muted">需先完成或跳过上一项</p>}
                  <div className="ai-workflow-actions">
                    {current && step.state === 'PENDING' && <>
                      <button type="button" className="ai-hub-primary" disabled={blocked} onClick={() => void operate(step.stepNo, 'approve')}>审核批准本项</button>
                      <button type="button" className="ai-hub-outline" disabled={blocked} onClick={() => void operate(step.stepNo, 'skip')}>跳过</button>
                    </>}
                    {current && step.state === 'APPROVED' && <>
                      <button type="button" className="ai-hub-primary" disabled={blocked} onClick={() => void operate(step.stepNo, 'execute')}>执行本项移动</button>
                      <button type="button" className="ai-hub-outline" disabled={blocked} onClick={() => void operate(step.stepNo, 'skip')}>放弃本项</button>
                    </>}
                    {step.state === 'APPLIED' && <button type="button" className="ai-hub-outline" disabled={blocked}
                      onClick={() => void operate(step.stepNo, 'undo')}>尝试条件撤销</button>}
                    {isFinishedStep(step.state) && step.state !== 'APPLIED' && <span className="ai-workflows-muted">无需进一步操作</span>}
                  </div>
                </div>
              </li>;
            })}
          </ol>
          <details className="ai-workflows-audit"><summary>查看事件审计记录（最近 40 条）</summary>
            {data.events.map((event, index) => <div key={index}>
              <span>{event.stepNo === 0 ? '计划' : `步骤 ${event.stepNo}`} · {actionLabels[event.action] ?? '状态变更'}</span>
              <time>{event.recordedAt}</time></div>)}
          </details>
          <p className="ai-workflows-muted">取消不等于回滚；条件撤销仍受权限、版本及时间检查约束。此界面不会后台执行下一步。</p>
        </>}
      </div>
    </div>
  </section>;
}
