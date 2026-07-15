import { httpClient, request } from '@/api/http';
import type { ArtifactAsset, AssetPage } from '@/types/api';

/**
 * 上传聊天附件；参数是文件和可选会话ID；返回服务端资产元数据。
 */
export function uploadChatAttachment(file: File, sessionId?: string) {
  const formData = new FormData();
  formData.append('file', file);
  if (sessionId) {
    formData.append('sessionId', sessionId);
  }
  return request<ArtifactAsset>({
    url: '/v1/assets/chat-attachments',
    method: 'POST',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

/**
 * 查询聊天资产；参数是游标、数量和可选会话；返回当前用户资产页。
 */
export function queryAssets(cursor?: string, limit = 50, sessionId?: string) {
  return request<AssetPage>({
    url: '/v1/assets',
    method: 'GET',
    params: { cursor, limit, sessionId: sessionId || undefined, kind: 'chat_attachment' },
  });
}

/**
 * 下载资产；参数是资产ID；返回受认证保护的文件 Blob。
 */
export async function downloadAsset(assetId: string) {
  const response = await httpClient.get<Blob>(`/v1/assets/${encodeURIComponent(assetId)}/download`, {
    responseType: 'blob',
  });
  return response.data;
}

/**
 * 删除资产；参数是资产ID；服务端软删除并保留审计关系。
 */
export function deleteAsset(assetId: string) {
  return request<void>({
    url: `/v1/assets/${encodeURIComponent(assetId)}`,
    method: 'DELETE',
  });
}
