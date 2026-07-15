import { request } from '@/api/http';
import type { ContextInsight, ModelUsageResponse } from '@/types/api';

/**
 * 查询会话上下文洞察；参数是会话ID；返回有效上下文的真实构成。
 */
export function queryContextInsight(sessionId: string) {
  return request<ContextInsight>({
    url: `/v1/sessions/${encodeURIComponent(sessionId)}/context-insight`,
    method: 'GET',
  });
}

/**
 * 查询会话模型用量；参数是会话和可选运行ID；返回最新调用与聚合结果。
 */
export function querySessionModelUsage(sessionId: string, runId?: string) {
  return request<ModelUsageResponse>({
    url: `/v1/sessions/${encodeURIComponent(sessionId)}/model-usage`,
    method: 'GET',
    params: { runId: runId || undefined },
  });
}

/**
 * 查询近期模型用量；参数是天数；返回当前用户聚合结果。
 */
export function queryRecentModelUsage(days = 1) {
  return request<ModelUsageResponse>({
    url: '/v1/model-usage/summary',
    method: 'GET',
    params: { days },
  });
}
