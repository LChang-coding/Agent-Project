import { getAccessToken, refreshAccessToken, requestWithTrace, resolveTraceId } from '@/api/http';
import { isTerminalWorkflowEvent } from '@/domain/workflow-event-reducer';
import { parseWorkflowSseBlock } from '@/domain/workflow-sse-parser';
import type {
  IntelligentWorkflowRunResponse,
  IntelligentWorkflowStartRequest,
  StaticWorkflowRunResponse,
  StaticWorkflowStartRequest,
  WorkflowRunEvent,
  WorkflowStreamMetadata,
} from '@/types/intelligent-workflow';
import { WORKFLOW_EVENT_SCHEMA } from '@/types/intelligent-workflow';

export async function startIntelligentWorkflow(payload: IntelligentWorkflowStartRequest) {
  const response = await requestWithTrace<IntelligentWorkflowRunResponse>({
    url: '/v1/intelligent-workflow-runs',
    method: 'POST',
    data: payload,
  });
  const traceId = response.data.traceId || response.traceId;
  if (!traceId) {
    throw new Error('智能工作流启动响应缺少根 Trace ID');
  }
  if (response.data.operationTraceId && response.traceId && response.data.operationTraceId !== response.traceId) {
    throw new Error('智能工作流启动连接 Trace ID 不一致');
  }
  return { ...response.data, traceId };
}

/** 启动与 HTTP 连接解耦的普通 DAG，节点和最终回答统一从持久事件流读取。 */
export async function startStaticWorkflow(payload: StaticWorkflowStartRequest) {
  const response = await requestWithTrace<StaticWorkflowRunResponse>({
    url: '/v1/workflow-runs',
    method: 'POST',
    data: payload,
  });
  const traceId = response.data.traceId || response.traceId;
  if (!traceId) throw new Error('普通工作流启动响应缺少根 Trace ID');
  if (response.data.operationTraceId && response.traceId && response.data.operationTraceId !== response.traceId) {
    throw new Error('普通工作流启动连接 Trace ID 不一致');
  }
  return { ...response.data, traceId };
}

export interface WorkflowStreamHandlers {
  onMetadata?: (metadata: WorkflowStreamMetadata) => void;
  onEvent: (event: WorkflowRunEvent) => void;
  signal?: AbortSignal;
}

export async function streamIntelligentWorkflow(
  runId: string,
  rootTraceId: string,
  afterSequence: number,
  handlers: WorkflowStreamHandlers,
  canRefresh = true,
): Promise<void> {
  return streamWorkflow(runId, rootTraceId, afterSequence, handlers, canRefresh);
}

/** 续传普通和智能工作流共用的 workflow-event-v1 事件。 */
export async function streamWorkflow(
  runId: string,
  rootTraceId: string,
  afterSequence: number,
  handlers: WorkflowStreamHandlers,
  canRefresh = true,
): Promise<void> {
  const response = await fetch(
    `${import.meta.env.VITE_API_BASE_URL || '/api'}/v1/workflow-runs/${encodeURIComponent(runId)}/stream?afterSequence=${Math.max(0, afterSequence)}`,
    { headers: { Accept: 'text/event-stream', Authorization: `Bearer ${getAccessToken()}` }, signal: handlers.signal },
  );
  const responseTraceId = resolveTraceId(response.headers);
  if (response.status === 401 && canRefresh) {
    await refreshAccessToken();
    return streamWorkflow(runId, rootTraceId, afterSequence, handlers, false);
  }
  if (!response.ok || !response.body) throw new Error(`工作流事件流请求失败：HTTP ${response.status}`);

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let metadataSeen = false;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const blocks = buffer.split(/\n\n|\r\n\r\n/);
      buffer = blocks.pop() || '';
      for (const block of blocks) {
        const result = parseWorkflowSseBlock(block);
        if (!result) continue;
        if (result.eventName === 'STREAM_METADATA') {
          const metadata = result.data as WorkflowStreamMetadata;
          assertMetadata(metadata, runId, rootTraceId, afterSequence, responseTraceId);
          metadataSeen = true;
          handlers.onMetadata?.(metadata);
        } else if (result.eventName === 'workflow_event') {
          if (!metadataSeen) throw new Error('工作流事件流缺少 STREAM_METADATA');
          const event = result.data as WorkflowRunEvent;
          if (event.runId !== runId || event.traceId !== rootTraceId) throw new Error('工作流事件根 Trace ID 或 Run 换号');
          handlers.onEvent(event);
          if (isTerminalWorkflowEvent(event)) {
            await reader.cancel();
            return;
          }
        } else if (result.eventName === 'error') {
          const error = result.data as { message?: string; traceId?: string };
          if (error.traceId && error.traceId !== rootTraceId) throw new Error('工作流错误事件根 Trace ID 换号');
          throw new Error(error.message || '工作流事件流失败');
        }
      }
    }
    throw new Error('工作流在终态事件前断开');
  } finally {
    reader.releaseLock();
  }
}

function assertMetadata(
  metadata: WorkflowStreamMetadata,
  runId: string,
  rootTraceId: string,
  afterSequence: number,
  responseTraceId: string,
) {
  if (metadata.schemaVersion !== WORKFLOW_EVENT_SCHEMA) throw new Error('工作流事件协议版本不匹配');
  if (metadata.runId !== runId || metadata.traceId !== rootTraceId) throw new Error('工作流元数据根 Trace ID 或 Run 换号');
  if (metadata.afterSequence !== Math.max(0, afterSequence)) throw new Error('工作流续传水位不一致');
  if (responseTraceId && metadata.operationTraceId && responseTraceId !== metadata.operationTraceId) {
    throw new Error('工作流连接 Trace ID 与元数据不一致');
  }
}
