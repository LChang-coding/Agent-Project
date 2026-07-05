import { getAccessToken, refreshAccessToken, request } from '@/api/http';
import type {
  AiAgentConfig,
  ChatRequest,
  ChatResponse,
  CreateSessionRequest,
  CreateSessionResponse,
  StreamHandlers,
} from '@/types/api';

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
 * 发送非流式聊天；参数是智能体、会话和消息；返回完整回复。
 */
export async function sendChatMessage(payload: ChatRequest) {
  return request<ChatResponse>({
    url: '/v1/chat',
    method: 'POST',
    data: payload,
  });
}

/**
 * 发送流式聊天；参数是聊天请求和回调；通过回调持续返回会话和内容片段。
 */
export async function sendChatMessageStream(payload: ChatRequest, handlers: StreamHandlers = {}) {
  return postStream(payload, handlers, true);
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
  handlers.onChunk?.(data);
}
