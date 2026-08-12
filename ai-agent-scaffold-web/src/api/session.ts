import { request } from '@/api/http';
import { getAccessToken, refreshAccessToken } from '@/api/http';
import { toSessionRagSettingRequest } from '@/api/session-rag-contract';
import type {
  SessionDeleteResponse,
  SessionListPage,
  SessionMessagePage,
  SessionOrchestrationSnapshot,
  SubagentTaskView,
  SessionRagSetting,
  SessionRagSettingUpdate,
} from '@/types/api';

export async function querySessionOrchestration(sessionId: string) {
  return request<SessionOrchestrationSnapshot>({
    url: `/v1/sessions/${encodeURIComponent(sessionId)}/orchestration`, method: 'GET',
  });
}

export async function cancelSessionSubagentTask(sessionId: string, taskId: string) {
  return request<{ taskId: string; cancelled: boolean }>({
    url: `/v1/sessions/${encodeURIComponent(sessionId)}/orchestration/tasks/${encodeURIComponent(taskId)}/cancel`, method: 'POST',
  });
}

export async function querySessionSubagentTask(sessionId: string, taskId: string) {
  return request<SubagentTaskView>({
    url: `/v1/sessions/${encodeURIComponent(sessionId)}/orchestration/tasks/${encodeURIComponent(taskId)}`, method: 'GET',
  });
}

/** 服务端推送完整快照；前端只按 version 去重，不轮询任务状态。 */
export async function streamSessionOrchestration(sessionId: string, afterVersion: string, signal: AbortSignal,
                                                  onSnapshot: (value: SessionOrchestrationSnapshot) => void,
                                                  retried = false): Promise<void> {
  const base = import.meta.env.VITE_API_BASE_URL || '/api';
  const url = `${base}/v1/sessions/${encodeURIComponent(sessionId)}/orchestration/stream?afterVersion=${encodeURIComponent(afterVersion)}`;
  const response = await fetch(url, { headers: { Accept: 'text/event-stream', Authorization: `Bearer ${getAccessToken()}` }, signal });
  if (response.status === 401 && !retried) {
    await refreshAccessToken();
    return streamSessionOrchestration(sessionId, afterVersion, signal, onSnapshot, true);
  }
  if (!response.ok || !response.body) throw new Error(`编排状态流连接失败：HTTP ${response.status}`);
  const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = '';
  while (true) {
    const { value, done } = await reader.read(); if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const blocks = buffer.split(/\n\n|\r\n\r\n/); buffer = blocks.pop() || '';
    for (const block of blocks) {
      if (!block.split(/\r?\n/).some((line) => line.trim() === 'event:orchestration_snapshot')) continue;
      const data = block.split(/\r?\n/).filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart()).join('\n');
      if (data) onSnapshot(JSON.parse(data) as SessionOrchestrationSnapshot);
    }
  }
}

/**
 * 查询数据库会话列表；参数是可选游标和数量；返回当前用户会话页。
 */
export async function queryChatSessions(cursor?: string, limit = 100) {
  return request<SessionListPage>({
    url: '/v1/sessions',
    method: 'GET',
    params: { cursor, limit },
  });
}

/**
 * 查询会话有效消息；参数是会话、前序号和数量；返回消息页。
 */
export async function queryChatSessionMessages(sessionId: string, beforeSequence?: number, limit = 100) {
  return request<SessionMessagePage>({
    url: `/v1/sessions/${encodeURIComponent(sessionId)}/messages`,
    method: 'GET',
    params: { beforeSequence, limit },
  });
}

/**
 * 软删除聊天会话；参数是会话ID；返回服务端删除结果。
 */
export async function deleteChatSession(sessionId: string) {
  return request<SessionDeleteResponse>({
    url: `/v1/sessions/${encodeURIComponent(sessionId)}`,
    method: 'DELETE',
  });
}

/** 查询会话RAG开关与目标绑定状态。 */
export async function querySessionRagSetting(sessionId: string) {
  return request<SessionRagSetting>({
    url: `/v1/sessions/${encodeURIComponent(sessionId)}/rag-setting`,
    method: 'GET',
  });
}

/** 持久化会话RAG策略；运行中的轮次不受影响。 */
export async function updateSessionRagSetting(sessionId: string, setting: SessionRagSettingUpdate) {
  return request<SessionRagSetting>({
    url: `/v1/sessions/${encodeURIComponent(sessionId)}/rag-setting`,
    method: 'PATCH',
    data: toSessionRagSettingRequest(setting),
  });
}
