import type {
  WorkflowNodeExecutionView,
  WorkflowRunEvent,
  WorkflowRunViewState,
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
    nodes: state.nodes.map((node) => ({ ...node })),
  };
  const payload = parsePayload(event.payloadJson);
  const node = event.nodeExecutionId ? findNode(next.nodes, event.nodeExecutionId) : undefined;

  switch (event.eventType) {
    case 'NODE_STARTED':
      if (!event.nodeExecutionId || !event.nodeId) throw new Error('NODE_STARTED 缺少节点执行标识');
      next.nodes.push({
        nodeExecutionId: event.nodeExecutionId,
        nodeId: event.nodeId,
        nodeName: text(payload.nodeName) || event.nodeId,
        executionIndex: number(payload.executionIndex, 1),
        status: 'running',
        output: '',
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
    case 'ROUTE_DECIDED': {
      const routed = requireNode(node, event);
      routed.routeTargetNodeId = text(payload.targetNodeId);
      routed.routeStrategy = text(payload.strategy);
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

function text(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function number<T extends number | undefined>(value: unknown, fallback: T): number | T {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}
