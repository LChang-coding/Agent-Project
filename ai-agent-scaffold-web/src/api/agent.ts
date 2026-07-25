import { getAccessToken, refreshAccessToken, request, requestWithTrace, resolveTraceId } from '@/api/http';
import type {
  AgentConfigItem,
  AgentMutationResponse,
  AgentStatusUpdateRequest,
  AiAgentConfig,
  ChatRequest,
  ChatResponse,
  CreateSessionRequest,
  CreateSessionResponse,
  RunControlResponse,
  RunStreamEvent,
  StreamHandlers,
} from '@/types/api';

/**
 * 查询 Agent 管理列表；参数控制是否包含禁用项；返回当前身份可管理状态。
 */
export async function queryAgentConfigManagement(includeDisabled = true) {
  return request<AgentConfigItem[]>({
    url: '/v1/agent-configs',
    method: 'GET',
    params: { includeDisabled },
  });
}

/**
 * 更新 Agent 有效状态；参数是 Agent ID 和幂等状态请求；返回最新修订。
 */
export async function updateAgentConfigStatus(agentId: string, payload: AgentStatusUpdateRequest) {
  return request<AgentMutationResponse>({
    url: `/v1/agent-configs/${encodeURIComponent(agentId)}/status`,
    method: 'PUT',
    data: payload,
  });
}

/**
 * 删除 Agent 管理覆盖；参数是 Agent ID；服务端幂等映射为禁用。
 */
export async function deleteAgentConfig(agentId: string, expectedRevision: number) {
  return request<AgentMutationResponse>({
    url: `/v1/agent-configs/${encodeURIComponent(agentId)}`,
    method: 'DELETE',
    params: { revision: expectedRevision },
  });
}

/**
 * 查询可用智能体；无需参数；返回后端已装配的智能体列表。
 */
export async function queryAgentConfigs() {
  return request<AiAgentConfig[]>({
    url: '/v1/query_ai_agent_config_list',
    method: 'GET',
  });
}

/**
 * 创建聊天会话；参数是智能体和兼容 userId；返回会话 ID。
 */
export async function createChatSession(payload: CreateSessionRequest) {
  return request<CreateSessionResponse>({
    url: '/v1/create_session',
    method: 'POST',
    data: payload,
  });
}

/**
 * 发送非流式聊天；参数是聊天请求和可选中断信号；返回完整回复。
 */
export async function sendChatMessage(payload: ChatRequest, signal?: AbortSignal) {
  const result = await requestWithTrace<ChatResponse>({
    url: '/v1/chat',
    method: 'POST',
    data: payload,
    signal,
  });
  return { ...result.data, traceId: result.traceId };
}

/**
 * 发送流式聊天；参数是聊天请求和回调；通过回调持续返回会话和内容片段。
 */
export async function sendChatMessageStream(payload: ChatRequest, handlers: StreamHandlers = {}) {
  return postStream(payload, handlers, true);
}

/**
 * 取消正在执行的运行；参数是运行 ID 和取消原因；返回服务端运行终态。
 */
export async function cancelChatRun(runId: string, reason = '用户主动取消') {
  return request<RunControlResponse>({
    url: `/v1/runs/${encodeURIComponent(runId)}/cancel`,
    method: 'POST',
    data: { reason },
  });
}

/**
 * 引导正在执行的运行；参数是运行 ID 和新指令；返回待启动的后继运行。
 */
export async function steerChatRun(runId: string, instruction: string) {
  return request<RunControlResponse>({
    url: `/v1/runs/${encodeURIComponent(runId)}/steer`,
    method: 'POST',
    data: { instruction },
  });
}

/**
 * 执行 POST SSE 请求；参数是聊天请求、回调和是否可刷新令牌；无返回值。
 */
async function postStream(payload: ChatRequest, handlers: StreamHandlers, canRefresh: boolean): Promise<void> {
  const response = await fetch(`${import.meta.env.VITE_API_BASE_URL || '/api'}/v1/chat_stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getAccessToken()}`,
    },
    body: JSON.stringify(payload),
    signal: handlers.signal,
  });
  const responseTraceId = resolveTraceId(response.headers);
  if (responseTraceId) {
    handlers.onTrace?.(responseTraceId);
  }

  if (response.status === 401 && canRefresh) {
    await refreshAccessToken();
    return postStream(payload, handlers, false);
  }

  if (!response.ok || !response.body) {
    const message = `流式请求失败：HTTP ${response.status}`;
    handlers.onError?.(message);
    throw new Error(message);
  }

  await readSseStream(response.body, handlers);
}

/**
 * 读取 SSE 响应流；参数是响应体和回调；逐段触发 session 或 chunk 回调。
 */
async function readSseStream(stream: ReadableStream<Uint8Array>, handlers: StreamHandlers) {
  const reader = stream.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split(/\n\n|\r\n\r\n/);
      buffer = parts.pop() || '';

      for (const part of parts) {
        handleSseBlock(part, handlers);
      }
    }

    if (buffer.trim()) {
      handleSseBlock(buffer, handlers);
    }
  } catch (error) {
    await reader.cancel().catch(() => undefined);
    throw error;
  } finally {
    reader.releaseLock();
  }
}

/**
 * 解析单个 SSE 数据块；参数是文本块和回调；按事件类型分发。
 */
function handleSseBlock(block: string, handlers: StreamHandlers) {
  const lines = block.split(/\r?\n/);
  const dataLines: string[] = [];
  let eventName = '';

  for (const line of lines) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim();
      continue;
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart());
    }
  }

  const data = dataLines.join('\n');
  if (!data || data === '[DONE]') {
    return;
  }
  if (eventName === 'session') {
    handlers.onSession?.(data);
    return;
  }
  if (eventName === 'trace') {
    try {
      const payload = JSON.parse(data) as { traceId?: string };
      if (payload.traceId) {
        handlers.onTrace?.(payload.traceId);
      }
    } catch {
      handlers.onTrace?.(data);
    }
    return;
  }
  if (eventName === 'run') {
    try {
      const run = JSON.parse(data) as RunStreamEvent;
      if (run.traceId) {
        handlers.onTrace?.(run.traceId);
      }
      handlers.onRun?.(run);
    } catch {
      const message = '运行信息解析失败';
      handlers.onError?.(message);
      throw new Error(message);
    }
    return;
  }
  if (eventName === 'error') {
    let message = data;
    try {
      const payload = JSON.parse(data) as { message?: string; code?: string; traceId?: string };
      if (payload.traceId) {
        handlers.onTrace?.(payload.traceId);
      }
      message = payload.message || payload.code || '流式请求失败';
    } catch {
      // 兼容服务端纯文本错误事件。
    }
    handlers.onError?.(message);
    throw new Error(message);
  }
  handlers.onChunk?.(data);
}
