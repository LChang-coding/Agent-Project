import { httpClient, request } from '@/api/http';
import type { SessionShareImportRequest, SessionShareResponse } from '@/types/api';

/**
 * 创建会话分享；参数是会话ID和可选生命周期；返回一次性分享链接。
 */
export function createSessionShare(sessionId: string, validHours = 72, maxDownloads = 20) {
  return request<SessionShareResponse>({
    url: '/v1/session-shares',
    method: 'POST',
    data: { sessionId, validHours, maxDownloads },
  });
}

/**
 * 查询分享预览；参数是原令牌；返回安全元数据。
 */
export function previewSessionShare(token: string) {
  return request<SessionShareResponse>({
    url: `/v1/session-shares/${encodeURIComponent(token)}/preview`,
    method: 'GET',
  });
}

/**
 * 复制导入分享；参数是原令牌；返回独立会话副本。
 */
export function importSessionShare(token: string, payload: SessionShareImportRequest) {
  return request<SessionShareResponse>({
    url: `/v1/session-shares/${encodeURIComponent(token)}/import`,
    method: 'POST',
    data: payload,
  });
}

/**
 * 下载服务端校验后的分享文件；参数是原令牌；触发浏览器保存。
 */
export async function downloadSessionShare(token: string) {
  const response = await httpClient.get<Blob>(`/v1/session-shares/${encodeURIComponent(token)}/download`, {
    responseType: 'blob',
  });
  const url = URL.createObjectURL(response.data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `chat-session-${new Date().toISOString().slice(0, 10)}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}

/**
 * 撤销本人分享；参数是分享ID；无返回值。
 */
export function revokeSessionShare(shareId: string) {
  return request<void>({
    url: `/v1/session-shares/${encodeURIComponent(shareId)}/revoke`,
    method: 'POST',
  });
}
