import assert from 'node:assert/strict';
import test from 'node:test';

import { parseWorkflowSseBlock } from '../src/domain/workflow-sse-parser.ts';
import { createWorkflowRunState, reduceWorkflowEvent } from '../src/domain/workflow-event-reducer.ts';
import { workflowHistoryRunTargets } from '../src/domain/workflow-history.ts';

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

test('刷新恢复为带根 Trace 的工作流 Run 建立一次回放，运行中只有 user 消息也可恢复', () => {
  assert.deepEqual(workflowHistoryRunTargets([
    { role: 'user', runId: 'run_1', traceId: 'trace_1' },
    { role: 'assistant', runId: 'run_1', traceId: 'trace_1' },
    { role: 'assistant', runId: 'run_2' },
    { role: 'assistant', runId: 'run_1', traceId: 'trace_1' },
    { role: 'assistant', runId: 'run_3', traceId: 'trace_3' },
    { role: 'user', runId: 'run_4', traceId: 'trace_4' },
  ]), [
    { runId: 'run_4', traceId: 'trace_4' },
    { runId: 'run_3', traceId: 'trace_3' },
    { runId: 'run_1', traceId: 'trace_1' },
  ]);
});

test('节点取消事件只收口对应并行执行实例', () => {
  let state = createWorkflowRunState(runId, traceId);
  state = reduceWorkflowEvent(state, event(1, 'WORKFLOW_STARTED'));
  state = reduceWorkflowEvent(state, event(2, 'NODE_STARTED', { nodeName: '分支甲' }, { nodeExecutionId: 'exec_a', nodeId: 'a' }));
  state = reduceWorkflowEvent(state, event(3, 'NODE_STARTED', { nodeName: '分支乙' }, { nodeExecutionId: 'exec_b', nodeId: 'b' }));
  state = reduceWorkflowEvent(state, event(4, 'NODE_CANCELLED', { message: '用户取消' }, { nodeExecutionId: 'exec_a', nodeId: 'a' }));
  assert.equal(state.nodes[0].status, 'cancelled');
  assert.equal(state.nodes[1].status, 'running');
});

test('工具调用按 functionCallId 归并并保留 RAG 结果摘要', () => {
  let state = createWorkflowRunState(runId, traceId);
  state = reduceWorkflowEvent(state, event(1, 'WORKFLOW_STARTED'));
  state = reduceWorkflowEvent(state, event(2, 'NODE_STARTED', { nodeName: '证据检查' }, { nodeExecutionId: 'exec_rag', nodeId: 'retrieve' }));
  state = reduceWorkflowEvent(state, event(3, 'TOOL_CALL_STARTED', {
    toolCode: 'platform_rag_retrieve_v1', displayName: '知识库检索', functionCallId: 'call_rag_1',
  }, { nodeExecutionId: 'exec_rag', nodeId: 'retrieve' }));
  state = reduceWorkflowEvent(state, event(4, 'TOOL_CALL_COMPLETED', {
    functionCallId: 'call_rag_1', success: true, costMs: 128, retrievalId: 'ret_1', hits: 6, citations: 2, tokens: 420, degraded: false,
  }, { nodeExecutionId: 'exec_rag', nodeId: 'retrieve' }));

  assert.deepEqual(state.nodes[0].toolCalls, [{
    functionCallId: 'call_rag_1',
    toolCode: 'platform_rag_retrieve_v1',
    displayName: '知识库检索',
    status: 'completed',
    startedAt: '2026-08-03T00:00:03',
    finishedAt: '2026-08-03T00:00:04',
    success: true,
    costMs: 128,
    retrievalId: 'ret_1',
    hits: 6,
    citations: 2,
    tokens: 420,
    degraded: false,
  }]);
});

test('工具失败、路由修复和扩展裁决保持权威来源及路由类别', () => {
  let state = createWorkflowRunState(runId, traceId);
  state = reduceWorkflowEvent(state, event(1, 'WORKFLOW_STARTED'));
  state = reduceWorkflowEvent(state, event(2, 'NODE_STARTED', { nodeName: '意图判定' }, { nodeExecutionId: 'exec_route', nodeId: 'classify' }));
  state = reduceWorkflowEvent(state, event(3, 'TOOL_CALL_STARTED', {
    toolCode: 'platform_select_workflow_route_v1', displayName: '智能路由', functionCallId: 'call_route_1',
  }, { nodeExecutionId: 'exec_route', nodeId: 'classify' }));
  state = reduceWorkflowEvent(state, event(4, 'TOOL_CALL_FAILED', {
    functionCallId: 'call_route_1', errorCode: 'WORKFLOW_ROUTE_KEY_INVALID', retryable: false, costMs: 9,
  }, { nodeExecutionId: 'exec_route', nodeId: 'classify' }));
  state = reduceWorkflowEvent(state, event(5, 'ROUTE_REPAIR_STARTED', {}, { nodeExecutionId: 'exec_route', nodeId: 'classify' }));
  state = reduceWorkflowEvent(state, event(6, 'ROUTE_REPAIR_COMPLETED', { success: true, routeKey: '账务' }, { nodeExecutionId: 'exec_route', nodeId: 'classify' }));
  state = reduceWorkflowEvent(state, event(7, 'ROUTE_DECIDED', {
    routeKey: '账务', targetNodeId: 'billing', targetNodeName: '账务处理', strategy: 'DEFAULT', source: 'ROUTE_REPAIR',
    reason: '账单金额存在疑问', functionCallId: 'repair_route_1', costMs: 32,
  }, { nodeExecutionId: 'exec_route', nodeId: 'classify' }));

  const node = state.nodes[0];
  assert.equal(node.toolCalls[0].status, 'failed');
  assert.equal(node.toolCalls[0].errorCode, 'WORKFLOW_ROUTE_KEY_INVALID');
  assert.equal(node.routeRepairStatus, 'completed');
  assert.equal(node.routeRepairRouteKey, '账务');
  assert.equal(node.routeKey, '账务');
  assert.equal(node.routeTargetNodeName, '账务处理');
  assert.equal(node.routeSource, 'ROUTE_REPAIR');
  assert.equal(node.routeReason, '账单金额存在疑问');
  assert.equal(node.routeFunctionCallId, 'repair_route_1');
  assert.equal(node.routeCostMs, 32);
  assert.equal(node.routeCategory, 'DEFAULT');
});
