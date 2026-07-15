import { request } from '@/api/http';
import type { SessionDeleteResponse, SessionListPage, SessionMessagePage } from '@/types/api';

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
