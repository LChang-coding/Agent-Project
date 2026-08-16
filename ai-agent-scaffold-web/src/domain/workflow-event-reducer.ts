import type {
  WorkflowNodeExecutionView,
  WorkflowRunEvent,
  WorkflowRunViewState,
  WorkflowToolCallView,
} from '@/types/intelligent-workflow';
import { WORKFLOW_EVENT_SCHEMA } from '@/types/intelligent-workflow';

type EventPayload = Record<string, unknown>;

export function createWorkflowRunState(runId: string, traceId: string): WorkflowRunViewState {
  return {
    runId,
    traceId,
    status: 'running',
    lastSequence: 0,
    seenEventIds: [],
    nodes: [],
    thinking: '',
    reactTurns: [],
    waitingAll: false,
    activities: [],
    finalAnswer: '',
    errorMessage: '',
  };
}

/** 归并单条持久化事件；重放事件幂等，序号缺口和根 Trace 换号立即拒绝。 */
export function reduceWorkflowEvent(state: WorkflowRunViewState, event: WorkflowRunEvent): WorkflowRunViewState {
  assertEnvelope(state, event);
  if (state.seenEventIds.includes(event.eventId)) return state;
  if (event.sequence <= state.lastSequence) throw new Error(`工作流事件序号 ${event.sequence} 出现不同 eventId`);
  if (event.sequence !== state.lastSequence + 1) {
    throw new Error(`工作流事件序号不连续：期望 ${state.lastSequence + 1}，实际 ${event.sequence}`);
  }

  const next: WorkflowRunViewState = {
    ...state,
    lastSequence: event.sequence,
    seenEventIds: [...state.seenEventIds, event.eventId],
    nodes: state.nodes.map((node) => ({ ...node, toolCalls: node.toolCalls.map((call) => ({ ...call })) })),
    reactTurns: state.reactTurns.map((turn) => ({ ...turn, tools: turn.tools.map((call) => ({ ...call })) })),
    activities: state.activities.map((activity) => ({ ...activity })),
  };
  const payload = parsePayload(event.payloadJson);
  const node = event.nodeExecutionId ? findNode(next.nodes, event.nodeExecutionId) : undefined;

  switch (event.eventType) {
    case 'AGENT_STARTED':
      next.activities.push({ id: event.eventId, type: 'agent', label: text(payload.label) || 'Agent 开始分析', status: 'running', startedAt: event.occurredAt });
      break;
    case 'THINKING_DELTA':
      appendThinking(next, event, text(payload.delta));
      break;
    case 'PARENT_RESUME_STARTED':
      next.waitingAll = false;
      next.activities.push({ id: event.eventId, type: 'agent', label: '主 Agent 已恢复，正在汇总', status: 'running',
        detail: text(payload.message), startedAt: event.occurredAt });
      break;
    case 'ANSWER_DELTA':
      next.finalAnswer += text(payload.delta);
      break;
    case 'WAITING_ALL':
      next.waitingAll = true;
      next.activities.push({ id: event.eventId, type: 'wait', label: '等待全部子 Agent', status: 'waiting',
        detail: text(payload.message), startedAt: event.occurredAt });
      break;
    case 'APPROVAL_REQUIRED':
      next.activities.push({ id: requiredText(payload.approvalId, 'APPROVAL_REQUIRED 缺少 approvalId'),
        type: 'approval', label: `等待授权·${text(payload.toolCode) || '工具调用'}`, status: 'waiting',
        detail: text(payload.message), startedAt: event.occurredAt });
      break;
    case 'APPROVAL_RESOLVED': {
      const approvalId = requiredText(payload.approvalId, 'APPROVAL_RESOLVED 缺少 approvalId');
      const approval = next.activities.find((candidate) => candidate.id === approvalId);
      if (!approval) throw new Error('APPROVAL_RESOLVED 找不到对应的 APPROVAL_REQUIRED');
      approval.status = text(payload.decision) === 'REJECT' ? 'failed' : 'completed';
      approval.detail = text(payload.decision);
      approval.finishedAt = event.occurredAt;
      break;
    }
    case 'NODE_STARTED':
      if (!event.nodeExecutionId || !event.nodeId) throw new Error('NODE_STARTED 缺少节点执行标识');
      next.nodes.push({
        nodeExecutionId: event.nodeExecutionId,
        nodeId: event.nodeId,
        nodeName: text(payload.nodeName) || event.nodeId,
        executionIndex: number(payload.executionIndex, 1),
        status: 'running',
        output: '',
        toolCalls: [],
        startedAt: event.occurredAt,
      });
      break;
    case 'NODE_OUTPUT_DELTA':
      requireNode(node, event).output += text(payload.delta);
      break;
    case 'NODE_COMPLETED': {
      const completed = requireNode(node, event);
      completed.status = 'completed';
      completed.output = text(payload.displayOutput) || completed.output;
      completed.totalTokens = number(payload.totalTokens, undefined);
      completed.finishedAt = event.occurredAt;
      break;
    }
    case 'NODE_FAILED': {
      const failed = requireNode(node, event);
      failed.status = 'failed';
      failed.errorMessage = text(payload.message) || text(payload.errorCode) || '节点执行失败';
      failed.finishedAt = event.occurredAt;
      break;
    }
    case 'NODE_CANCELLED': {
      const cancelled = requireNode(node, event);
      cancelled.status = 'cancelled';
      cancelled.errorMessage = text(payload.message) || '节点执行已取消';
      cancelled.finishedAt = event.occurredAt;
      break;
    }
    case 'TOOL_CALL_STARTED': {
      const functionCallId = requiredText(payload.functionCallId, 'TOOL_CALL_STARTED 缺少 functionCallId');
      if (!node) {
        currentReactTurn(next, event).tools.push({
          functionCallId,
          toolCode: text(payload.toolCode),
          displayName: text(payload.displayName) || text(payload.toolCode) || '工具调用',
          status: 'running',
          startedAt: event.occurredAt,
        });
        break;
      }
      const owner = requireNode(node, event);
      owner.toolCalls.push({
        functionCallId,
        toolCode: text(payload.toolCode),
        displayName: text(payload.displayName) || text(payload.toolCode) || '工具调用',
        status: 'running',
        startedAt: event.occurredAt,
      });
      break;
    }
    case 'TOOL_CALL_COMPLETED':
    case 'TOOL_CALL_FAILED': {
      const functionCallId = requiredText(payload.functionCallId, `${event.eventType} 缺少 functionCallId`);
      if (!node) {
        const call = next.reactTurns.flatMap((turn) => turn.tools)
          .find((candidate) => candidate.functionCallId === functionCallId);
        if (!call) throw new Error(`${event.eventType} 找不到对应的 TOOL_CALL_STARTED`);
        applyToolResult(call, event, payload);
        break;
      }
      const owner = requireNode(node, event);
      const call = owner.toolCalls.find((candidate) => candidate.functionCallId === functionCallId);
      if (!call) throw new Error(`${event.eventType} 找不到对应的 TOOL_CALL_STARTED`);
      applyToolResult(call, event, payload);
      break;
    }
    case 'ROUTE_REPAIR_STARTED':
      requireNode(node, event).routeRepairStatus = 'running';
      break;
    case 'ROUTE_REPAIR_COMPLETED': {
      const repaired = requireNode(node, event);
      repaired.routeRepairStatus = 'completed';
      repaired.routeRepairRouteKey = optionalText(payload.routeKey);
      break;
    }
    case 'ROUTE_DECIDED': {
      const routed = requireNode(node, event);
      routed.routeTargetNodeId = text(payload.targetNodeId);
      routed.routeStrategy = text(payload.strategy);
      routed.routeKey = optionalText(payload.routeKey);
      routed.routeTargetNodeName = optionalText(payload.targetNodeName);
      routed.routeSource = optionalText(payload.source);
      routed.routeReason = optionalText(payload.reason);
      routed.routeFunctionCallId = optionalText(payload.functionCallId);
      routed.routeCostMs = number(payload.costMs, undefined);
      routed.routeCategory = routed.routeStrategy === 'FAILURE'
        ? 'FAILURE'
        : routed.routeStrategy === 'DEFAULT' ? 'DEFAULT' : 'BUSINESS';
      break;
    }
    case 'FINAL_ANSWER_DELTA':
      next.finalAnswer += text(payload.delta);
      break;
    case 'FINAL_ANSWER_COMPLETED':
      next.finalAnswer = text(payload.content) || next.finalAnswer;
      break;
    case 'WORKFLOW_COMPLETED':
      next.status = 'completed';
      next.waitingAll = false;
      next.activities = next.activities.map((activity) => activity.status === 'running'
        ? { ...activity, status: 'completed', finishedAt: event.occurredAt } : activity);
      break;
    case 'WORKFLOW_FAILED':
      next.status = 'failed';
      next.errorMessage = text(payload.message) || text(payload.errorCode) || '工作流执行失败';
      break;
    case 'WORKFLOW_CANCELLED':
      next.status = 'cancelled';
      break;
    default:
      break;
  }
  return next;
}

export function isTerminalWorkflowEvent(event: WorkflowRunEvent) {
  return ['WORKFLOW_COMPLETED', 'WORKFLOW_FAILED', 'WORKFLOW_CANCELLED'].includes(event.eventType);
}

function assertEnvelope(state: WorkflowRunViewState, event: WorkflowRunEvent) {
  if (event.schemaVersion !== WORKFLOW_EVENT_SCHEMA) throw new Error(`不支持的工作流事件版本：${event.schemaVersion}`);
  if (event.runId !== state.runId) throw new Error('工作流事件所属 Run 不一致');
  if (!event.traceId || event.traceId !== state.traceId) throw new Error('工作流根 Trace ID 不一致');
  if (!event.eventId || !Number.isInteger(event.sequence) || event.sequence < 1) throw new Error('工作流事件信封不完整');
}

function parsePayload(value: string): EventPayload {
  try {
    return JSON.parse(value || '{}') as EventPayload;
  } catch {
    throw new Error('工作流事件 payloadJson 不是有效 JSON');
  }
}

function findNode(nodes: WorkflowNodeExecutionView[], executionId: string) {
  return nodes.find((node) => node.nodeExecutionId === executionId);
}

function requireNode(node: WorkflowNodeExecutionView | undefined, event: WorkflowRunEvent) {
  if (!node) throw new Error(`${event.eventType} 找不到对应的 NODE_STARTED`);
  return node;
}

function appendThinking(state: WorkflowRunViewState, event: WorkflowRunEvent, delta: string) {
  state.thinking += delta;
  const last = state.reactTurns.at(-1);
  const turn = !last || last.tools.length > 0 ? currentReactTurn(state, event, true) : last;
  turn.thinking += delta;
}

function currentReactTurn(state: WorkflowRunViewState, event: WorkflowRunEvent, forceNew = false) {
  let turn = forceNew ? undefined : state.reactTurns.at(-1);
  if (!turn) {
    turn = { id: `react-${event.eventId}`, thinking: '', tools: [], startedAt: event.occurredAt };
    state.reactTurns.push(turn);
  }
  return turn;
}

function applyToolResult(call: WorkflowToolCallView,
                         event: WorkflowRunEvent, payload: EventPayload) {
  call.status = event.eventType === 'TOOL_CALL_COMPLETED' ? 'completed' : 'failed';
  call.finishedAt = event.occurredAt;
  call.success = boolean(payload.success, event.eventType === 'TOOL_CALL_COMPLETED');
  assignOptional(call, 'costMs', number(payload.costMs, undefined));
  assignOptional(call, 'retrievalId', optionalText(payload.retrievalId));
  assignOptional(call, 'hits', number(payload.hits, undefined));
  assignOptional(call, 'citations', number(payload.citations, undefined));
  assignOptional(call, 'tokens', number(payload.tokens, undefined));
  assignOptional(call, 'degraded', optionalBoolean(payload.degraded));
  assignOptional(call, 'routeKey', optionalText(payload.routeKey));
  assignOptional(call, 'reason', optionalText(payload.reason));
  assignOptional(call, 'errorCode', optionalText(payload.errorCode));
  assignOptional(call, 'retryable', optionalBoolean(payload.retryable));
}

function text(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function optionalText(value: unknown) {
  const result = text(value);
  return result || undefined;
}

function requiredText(value: unknown, message: string) {
  const result = text(value);
  if (!result) throw new Error(message);
  return result;
}

function boolean(value: unknown, fallback: boolean) {
  return typeof value === 'boolean' ? value : fallback;
}

function optionalBoolean(value: unknown) {
  return typeof value === 'boolean' ? value : undefined;
}

function assignOptional<T extends object, K extends keyof T>(target: T, key: K, value: T[K] | undefined) {
  if (value !== undefined) target[key] = value;
}

function number<T extends number | undefined>(value: unknown, fallback: T): number | T {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}
