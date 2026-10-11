import type { AgentWorkflow, AgentWorkflowStep, BatchMovePreview } from './knowledge';

export const isFinishedStep = (state: AgentWorkflowStep['state']) =>
  state === 'APPLIED' || state === 'SKIPPED' || state === 'REVERTED';

/** A display projection, never a substitute for the server's authorization checks. */
export function workflowProgress(workflow: AgentWorkflow) {
  const completed = workflow.steps.filter(step => isFinishedStep(step.state)).length;
  const next = workflow.summary.status === 'ACTIVE'
    ? workflow.steps.find(step => !isFinishedStep(step.state)) ?? null : null;
  return {
    completed,
    total: workflow.steps.length,
    percent: workflow.steps.length ? Math.round(completed * 100 / workflow.steps.length) : 0,
    next,
    instruction: workflow.summary.status === 'CANCELLED'
      ? '计划已取消。已执行的移动不会自动恢复。'
      : !next ? '计划已处理完毕。已执行项目仍须通过服务端条件检查才能撤销。'
      : next.state === 'APPROVED'
        ? `下一步：确认并执行第 ${next.stepNo} 项；不会执行其他文件。`
        : `下一步：核对第 ${next.stepNo} 项的文件和目标目录，再批准或跳过。`,
  };
}

/** Applied/reverted proposals must not be silently submitted to a new workflow. */
export function pendingPlanIds(preview: BatchMovePreview, applied: string[], undone: string[]) {
  const excluded = new Set([...applied, ...undone]);
  return [...new Set(preview.items.flatMap(item =>
    item.status === 'READY' && item.proposal?.status === 'PENDING' && !excluded.has(item.proposal.id)
      ? [item.proposal.id] : []))];
}

/** React state updates are asynchronous; use a synchronous lease for write exclusion. */
export function createOperationGate() {
  let active: symbol | null = null;
  return {
    acquire(): symbol | null {
      if (active !== null) return null;
      active = Symbol('workflow-operation');
      return active;
    },
    release(lease: symbol) { if (active === lease) active = null; },
  };
}
