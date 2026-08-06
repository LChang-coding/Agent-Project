import type { SessionRagSettingUpdate } from '@/types/api';

/** 将前端RAG设置转换为后端请求协议。 */
export function toSessionRagSettingRequest(setting: SessionRagSettingUpdate) {
  return {
    ...setting,
    // 兼容尚未移除布尔字段的服务端和链路观察者。
    enabled: setting.mode !== 'OFF',
  };
}
