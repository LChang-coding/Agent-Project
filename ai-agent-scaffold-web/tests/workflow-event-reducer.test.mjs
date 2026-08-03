import assert from 'node:assert/strict';
import test from 'node:test';

import { parseWorkflowSseBlock } from '../src/domain/workflow-sse-parser.ts';
import { createWorkflowRunState, reduceWorkflowEvent } from '../src/domain/workflow-event-reducer.ts';

const runId = 'run_1';
const traceId = 'trace_root';

function event(sequence, eventType, payload = {}, overrides = {}) {
  return { schemaVersion: 'workflow-event-v1', eventId: `event_${sequence}`, sequence, runId, eventType,
    payloadJson: JSON.stringify(payload), traceId, occurredAt: `2026-08-03T00:00:0${sequence}`, ...overrides };
}

test('节点输出与最终回答分离归并', () => {
  let state = createWorkflowRunState(runId, traceId);
  state = reduceWorkflowEvent(state, event(1, 'WORKFLOW_STARTED'));
  state = reduceWorkflowEvent(state, event(2, 'NODE_STARTED', { nodeName: '审核', executionIndex: 1 }, { nodeExecutionId: 'exec_1', nodeId: 'review' }));
  state = reduceWorkflowEvent(state, event(3, 'NODE_OUTPUT_DELTA', { delta: '中间分析' }, { nodeExecutionId: 'exec_1', nodeId: 'review' }));
  state = reduceWorkflowEvent(state, event(4, 'NODE_COMPLETED', { displayOutput: '中间分析', totalTokens: 12 }, { nodeExecutionId: 'exec_1', nodeId: 'review' }));
  state = reduceWorkflowEvent(state, event(5, 'ROUTE_DECIDED', { strategy: 'DEFAULT', targetNodeId: 'END' }, { nodeExecutionId: 'exec_1', nodeId: 'review' }));
  state = reduceWorkflowEvent(state, event(6, 'FINAL_ANSWER_DELTA', { delta: '最终答案' }));
  state = reduceWorkflowEvent(state, event(7, 'WORKFLOW_COMPLETED'));
  assert.equal(state.nodes[0].output, '中间分析');
  assert.equal(state.finalAnswer, '最终答案');
  assert.equal(state.nodes[0].routeTargetNodeId, 'END');
  assert.equal(state.status, 'completed');
});

test('同 eventId 重放幂等，序号缺口、换号和同序号异事件被拒绝', () => {
  const first = event(1, 'WORKFLOW_STARTED');
  const state = reduceWorkflowEvent(createWorkflowRunState(runId, traceId), first);
  assert.equal(reduceWorkflowEvent(state, first), state);
  assert.throws(() => reduceWorkflowEvent(state, event(3, 'WORKFLOW_COMPLETED')), /序号不连续/);
  assert.throws(() => reduceWorkflowEvent(state, event(2, 'WORKFLOW_COMPLETED', {}, { traceId: 'other' })), /Trace ID/);
  assert.throws(() => reduceWorkflowEvent(state, event(1, 'WORKFLOW_STARTED', {}, { eventId: 'collision' })), /不同 eventId/);
});

test('SSE parser 支持多行 data 并拒绝非 JSON', () => {
  const parsed = parseWorkflowSseBlock('event: workflow_event\ndata: {"runId":"run_1",\ndata: "sequence":1}');
  assert.deepEqual(parsed, { eventName: 'workflow_event', data: { runId: 'run_1', sequence: 1 } });
  assert.throws(() => parseWorkflowSseBlock('event: workflow_event\ndata: nope'), /不是有效 JSON/);
});
