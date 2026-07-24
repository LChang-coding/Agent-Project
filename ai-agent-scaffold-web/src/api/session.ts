import { request } from '@/api/http';
import type {
  SessionDeleteResponse,
  SessionListPage,
  SessionMessagePage,
  SessionRagSetting,
  SessionRagSettingUpdate,
} from '@/types/api';

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
    data: {
      ...setting,
      // 兼容尚未移除布尔字段的服务端和链路观察者。
      enabled: setting.mode !== 'OFF',
    },
  });
}
