import { useEffect, useRef, useState } from 'react';
import { knowledgeApi, type AgentWorkflow, type AgentWorkflowStep, type AgentWorkflowSummary } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';

const stateLabels: Record<AgentWorkflowStep['state'], string> = {
  PENDING: '待审核',
  APPROVED: '已批准，待执行',
  APPLIED: '已执行',
  SKIPPED: '已跳过',
  REVERTED: '已条件撤销',
};

const actionLabels: Record<string, string> = {
  CREATED: '创建执行计划',
  APPROVED: '批准当前步骤',
  APPLIED: '执行当前步骤',
  SKIPPED: '跳过当前步骤',
  REVERTED: '条件撤销',
  CANCELLED: '取消剩余计划',
};

const terminal = (state: AgentWorkflowStep['state']) =>
  state === 'APPLIED' || state === 'REVERTED' || state === 'SKIPPED';

/** Durable user-owned plan: never auto-advances or batches file mutations. */
export function AgentWorkflowPanel({ initialId }: { initialId?: string }) {
  const [plans, setPlans] = useState<AgentWorkflowSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(initialId ?? null);
  const [data, setData] = useState<AgentWorkflow | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);
  const active = useRef<AbortController | null>(null);

  useEffect(() => {
    if (initialId) setSelectedId(initialId);
  }, [initialId]);

  useEffect(() => {
    const request = new AbortController();
    setLoading(true); setError('');
    void knowledgeApi.recentWorkflows(request.signal)
      .then(items => { if (!request.signal.aborted) setPlans(items); })
      .catch(cause => { if (!request.signal.aborted) setError(cause instanceof Error ? cause.message : '无法读取计划列表'); })
      .finally(() => { if (!request.signal.aborted) setLoading(false); });
    return () => request.abort();
  }, [revision]);

  useEffect(() => {
    const request = new AbortController();
    if (!selectedId) { setData(null); return () => request.abort(); }
    setError(''); setData(null);
    void knowledgeApi.workflow(selectedId, request.signal)
      .then(result => { if (!request.signal.aborted) setData(result); })
      .catch(cause => { if (!request.signal.aborted) setError(cause instanceof Error ? cause.message : '计划已失效或无权查看'); });
    return () => request.abort();
  }, [selectedId, revision]);

  useEffect(() => () => active.current?.abort(), []);

  const operate = async (stepNo: number | null, action: 'approve' | 'skip' | 'execute' | 'undo' | 'cancel') => {
    if (!data || busy) return;
    const prompt: Record<typeof action, string> = {
      approve: '仅批准本条文件移动建议？批准本身不会移动文件，执行需要再次确认。',
      skip: '确定跳过这一条建议？文件不会改变，下一步才会解锁。',
      execute: '确认现在执行这一条文件移动？执行前会再次校验文件权限、同名冲突和版本。不会自动执行后续步骤。',
      undo: '尝试撤销已执行的这一步？仅限原有 15 分钟且文件及目录未被后续修改的条件。',
      cancel: '确定取消这个计划后续的未执行步骤？已执行移动不会自动撤回，仍需逐项条件撤销。',
    };
    if (!window.confirm(prompt[action])) return;
    const ctrl = new AbortController();
    active.current = ctrl;
    setBusy(true); setError(''); setNotice('');
    try {
      const next = action === 'cancel'
        ? await knowledgeApi.cancelWorkflow(data.summary.id, ctrl.signal)
        : await knowledgeApi.workflowAction(data.summary.id, stepNo!, action, ctrl.signal);
      if (ctrl.signal.aborted) return;
      setData(next);
      setNotice(action === 'execute' ? '仅当前步骤执行成功；后续步骤仍需要独立批准。'
        : action === 'undo' ? '撤销条件已通过，当前文件位置恢复。'
        : action === 'approve' ? '当前步骤已批准；仍需单独点击「执行本项」。'
        : action === 'cancel' ? '已取消后续计划，已执行的文件不会自动移动。' : '本项已跳过。');
      setRevision(n => n + 1);
    } catch (cause) {
      if (!ctrl.signal.aborted) setError(cause instanceof Error ? cause.message : '动作被安全校验阻断，请刷新后重试');
    } finally {
      if (active.current === ctrl) { active.current = null; setBusy(false); }
    }
  };

  return <section className="ai-workflows" aria-label="受控 AI Agent 工作流">
    <div className="ai-hub-section-head">
      <div><span className="ai-hub-eyebrow">REVIEWED WORKFLOWS</span>
        <h2>多步骤整理计划</h2>
        <p>从 AI 生成的独立移动建议中创建计划。每一步按顺序审核、确认和执行，没有自动批量写入。</p>
      </div>
      <button type="button" className="ai-hub-outline" disabled={busy || loading}
        onClick={() => setRevision(n => n + 1)}><MaterialIcon>refresh</MaterialIcon>刷新计划</button>
    </div>
    {error && <p role="alert" className="ai-hub-notice">{error}</p>}
    {notice && <p role="status" className="ai-hub-success">{notice}</p>}
    <div className="ai-workflows-layout">
      <aside className="ai-workflows-list" aria-label="最近的工作流">
        <h3>最近 20 个计划</h3>
        {loading && <p className="ai-workflows-muted">正在加载记录…</p>}
        {!loading && plans.length === 0 && <p className="ai-workflows-muted">还没有执行计划。请先搜索资料并生成批量移动预览。</p>}
        {plans.map(plan => <button key={plan.id} type="button"
          className={selectedId === plan.id ? 'ai-workflows-selected' : ''}
          onClick={() => { setSelectedId(plan.id); setNotice(''); setError(''); }} disabled={busy}>
          <span className="ai-workflows-list-title"><MaterialIcon>account_tree</MaterialIcon>
            {plan.total} 步 · {plan.status === 'ACTIVE' ? '进行中' : plan.status === 'COMPLETED' ? '已完成' : '已取消'}
          </span>
          <small>{plan.applied} 已执行 · {plan.pending} 未完成 · {plan.createdAt}</small>
        </button>)}
      </aside>

      <div className="ai-workflows-details">
        {!selectedId && <div className="ai-workflows-empty"><MaterialIcon>assignment</MaterialIcon>选择一个计划，查看分步审批和执行记录。</div>}
        {selectedId && !data && !error && <div className="ai-workflows-empty">正在按当前账户权限读取计划…</div>}
        {data && <>
          <div className="ai-workflows-heading">
            <div><h3>执行计划 · {data.summary.total} 步</h3>
              <p>状态：{data.summary.status === 'ACTIVE' ? '进行中' : data.summary.status === 'COMPLETED' ? '已完成' : '已取消'}
                · {data.summary.applied} 已执行 · {data.summary.pending} 待处理</p></div>
            {data.summary.status === 'ACTIVE' && data.summary.pending > 0 && (
              <button type="button" className="ai-hub-outline" disabled={busy}
                onClick={() => void operate(null, 'cancel')}>取消剩余计划</button>
            )}
          </div>
          <ol className="ai-workflow-steps">
            {data.steps.map((step, i) => {
              const ready = data.steps.slice(0, i).every(previous => terminal(previous.state));
              const activeStep = data.summary.status === 'ACTIVE' && ready;
              return <li key={step.moveProposalId}>
                <div className="ai-workflow-marker">{step.stepNo}</div>
                <div className="ai-workflow-step">
                  <div className="ai-workflow-step-top">
                    <strong title={step.fileName}>{step.fileName}</strong>
                    <span className={`ai-workflow-state ai-workflow-state-${step.state.toLowerCase()}`}>{stateLabels[step.state]}</span>
                  </div>
                  <p>拟移动至：<b>{step.destinationName}</b></p>
                  <small>建议截止（服务端时间）：{step.expiresAt} · 每次执行都会重新校验授权和目录状态</small>
                  {data.summary.status === 'ACTIVE' && !ready && !terminal(step.state) &&
                    <p className="ai-workflows-muted">需先完成或跳过上一项</p>}
                  <div className="ai-workflow-actions">
                    {activeStep && step.state === 'PENDING' && <>
                      <button type="button" className="ai-hub-primary" disabled={busy}
                        onClick={() => void operate(step.stepNo,'approve')}>审核批准本项</button>
                      <button type="button" className="ai-hub-outline" disabled={busy}
                        onClick={() => void operate(step.stepNo,'skip')}>跳过</button>
                    </>}
                    {activeStep && step.state === 'APPROVED' && <>
                      <button type="button" className="ai-hub-primary" disabled={busy}
                        onClick={() => void operate(step.stepNo,'execute')}>执行本项移动</button>
                      <button type="button" className="ai-hub-outline" disabled={busy}
                        onClick={() => void operate(step.stepNo,'skip')}>放弃本项</button>
                    </>}
                    {step.state === 'APPLIED' && <button type="button" className="ai-hub-outline" disabled={busy}
                      onClick={() => void operate(step.stepNo,'undo')}>尝试条件撤销</button>}
                    {terminal(step.state) && step.state !== 'APPLIED' && <span className="ai-workflows-muted">无需进一步操作</span>}
                  </div>
                </div>
              </li>;
            })}
          </ol>
          <details className="ai-workflows-audit">
            <summary>查看事件审计记录（最近 40 条）</summary>
            {data.events.map((event,index) => <div key={index}>
              <span>{event.stepNo === 0 ? '计划' : `步骤 ${event.stepNo}`} · {actionLabels[event.action] ?? '状态变更'}</span>
              <time>{event.recordedAt}</time>
            </div>)}
          </details>
          <p className="ai-workflows-muted">计划取消不等于文件回滚；已执行文件的条件撤销仍必须通过原有的权限、版本及时间检查。该界面不会后台自动执行下一步。</p>
        </>}
      </div>
    </div>
  </section>;
}
