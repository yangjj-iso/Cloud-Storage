import test from 'node:test';
import assert from 'node:assert/strict';
import { createOperationGate, isFinishedStep, pendingPlanIds, workflowProgress } from '../src/lib/workflowExperience.ts';

const workflow = (states, status = 'ACTIVE') => ({
  summary: { id: 'plan', status },
  steps: states.map((state, i) => ({ stepNo: i + 1, state })),
});

test('approval is not execution and does not advance the plan', () => {
  const p = workflowProgress(workflow(['APPROVED', 'PENDING']));
  assert.equal(p.completed, 0); assert.equal(p.next.stepNo, 1);
  assert.match(p.instruction, /确认并执行第 1 项/);
});
test('only the first unfinished step is actionable', () => {
  const p = workflowProgress(workflow(['SKIPPED', 'REVERTED', 'PENDING', 'PENDING']));
  assert.equal(p.completed, 2); assert.equal(p.percent, 50); assert.equal(p.next.stepNo, 3);
});
test('cancelled plans never offer a next step', () => {
  const p = workflowProgress(workflow(['APPLIED', 'PENDING'], 'CANCELLED'));
  assert.equal(p.next, null); assert.match(p.instruction, /不会自动恢复/);
});
test('empty plans have finite zero progress', () => {
  const p = workflowProgress(workflow([]));
  assert.equal(p.percent, 0); assert.equal(p.total, 0); assert.equal(p.next, null);
});
test('completed plans stay complete after conditional undo', () => {
  const p = workflowProgress(workflow(['REVERTED', 'SKIPPED'], 'COMPLETED'));
  assert.equal(p.percent, 100); assert.equal(p.next, null);
});
test('pending and approved states are never terminal', () => {
  for (const state of ['PENDING', 'APPROVED']) assert.equal(isFinishedStep(state), false);
  for (const state of ['APPLIED', 'REVERTED', 'SKIPPED']) assert.equal(isFinishedStep(state), true);
});
test('save only unique ready, pending, not locally executed proposals', () => {
  const item = (id, status = 'PENDING') => ({ status: 'READY', proposal: { id, status } });
  const preview = { items: [item('a'), item('a'), item('b'), item('c'), item('d', 'APPLIED'),
    item('e', 'REVERTED'), { status: 'SKIPPED', proposal: null }, { status: 'SKIPPED', proposal: {id:'f',status:'PENDING'} }] };
  assert.deepEqual(pendingPlanIds(preview, ['b'], ['c']), ['a']);
});
test('rapid duplicate acquisitions are rejected before React can rerender', () => {
  const gate = createOperationGate(); const first = gate.acquire();
  assert.equal(typeof first, 'symbol'); assert.equal(gate.acquire(), null);
  gate.release(first); assert.equal(typeof gate.acquire(), 'symbol');
});
test('stale completion cannot unlock a newer operation', () => {
  const gate = createOperationGate(); const first = gate.acquire(); gate.release(first);
  const second = gate.acquire(); gate.release(first); assert.equal(gate.acquire(), null);
  gate.release(second); assert.notEqual(gate.acquire(), null);
});
