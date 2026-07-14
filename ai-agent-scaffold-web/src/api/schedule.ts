import { request } from '@/api/http';
import type {
  ScheduleConfig,
  ScheduleExecution,
  ScheduleSaveRequest,
} from '@/types/api';

/** 查询当前用户的定时任务配置。 */
export function querySchedules() {
  return request<ScheduleConfig[]>({ url: '/v1/schedules', method: 'GET' });
}

/** 创建定时任务配置。 */
export function createSchedule(payload: ScheduleSaveRequest) {
  return request<ScheduleConfig>({ url: '/v1/schedules', method: 'POST', data: payload });
}

/** 修改本人定时任务配置。 */
export function updateSchedule(configId: string, payload: ScheduleSaveRequest) {
  return request<ScheduleConfig>({
    url: `/v1/schedules/${encodeURIComponent(configId)}`,
    method: 'PUT',
    data: payload,
  });
}

/** 启用或停用本人定时任务。 */
export function setScheduleEnabled(configId: string, enabled: boolean) {
  return request<ScheduleConfig>({
    url: `/v1/schedules/${encodeURIComponent(configId)}/${enabled ? 'enable' : 'disable'}`,
    method: 'POST',
  });
}

/** 立即把配置对应运行态推进为到期。 */
export function triggerSchedule(configId: string, retry = false) {
  return request<void>({
    url: `/v1/schedules/${encodeURIComponent(configId)}/${retry ? 'retry' : 'trigger'}`,
    method: 'POST',
  });
}

/** 查询配置的逻辑执行历史。 */
export function queryScheduleExecutions(configId: string, limit = 50) {
  return request<ScheduleExecution[]>({
    url: `/v1/schedules/${encodeURIComponent(configId)}/executions`,
    method: 'GET',
    params: { limit },
  });
}

/** 预览 Spring 六段式 Cron 的后续 UTC 时间。 */
export function previewScheduleCron(cron: string, timezone: string, count = 5) {
  return request<string[]>({
    url: '/v1/schedules/cron-preview',
    method: 'GET',
    params: { cron, timezone, count },
  });
}
