import { defineStore } from 'pinia';

import { queryContextInsight, queryRecentModelUsage, querySessionModelUsage } from '@/api/insight';
import type { ContextInsight, ModelUsageResponse, ModelUsageSummary } from '@/types/api';

let sessionRequestGeneration = 0;
let recentRequestGeneration = 0;

interface InsightState {
  sessionId: string;
  runId: string;
  context: ContextInsight | null;
  usage: ModelUsageResponse | null;
  recent: ModelUsageSummary | null;
  loadingSession: boolean;
  loadingRecent: boolean;
  sessionError: string;
  recentError: string;
}

/**
 * 上下文与模型用量 Store。
 * <p>负责按会话隔离异步结果，避免快速切换时旧请求回写。</p>
 */
export const useInsightStore = defineStore('insight', {
  state: (): InsightState => ({
    sessionId: '',
    runId: '',
    context: null,
    usage: null,
    recent: null,
    loadingSession: false,
    loadingRecent: false,
    sessionError: '',
    recentError: '',
  }),
  actions: {
    /**
     * 加载会话洞察；参数是会话和可选运行ID；并行刷新上下文与用量。
     */
    async loadSession(sessionId: string, runId = '') {
      const generation = ++sessionRequestGeneration;
      const scopeChanged = this.sessionId !== sessionId || this.runId !== runId;
      this.sessionId = sessionId;
      this.runId = runId;
      this.sessionError = '';
      if (scopeChanged) {
        this.context = null;
        this.usage = null;
      }
      if (!sessionId) {
        this.context = null;
        this.usage = null;
        this.loadingSession = false;
        return;
      }
      this.loadingSession = true;
      const [contextResult, usageResult] = await Promise.allSettled([
        queryContextInsight(sessionId),
        querySessionModelUsage(sessionId, runId),
      ]);
      if (generation !== sessionRequestGeneration || this.sessionId !== sessionId || this.runId !== runId) {
        return;
      }
      if (contextResult.status === 'fulfilled') {
        this.context = contextResult.value;
      } else {
        this.context = null;
      }
      if (usageResult.status === 'fulfilled') {
        this.usage = usageResult.value;
      } else {
        this.usage = null;
      }
      const errors = [contextResult, usageResult]
        .filter((result): result is PromiseRejectedResult => result.status === 'rejected')
        .map((result) => result.reason instanceof Error ? result.reason.message : '统计请求失败');
      this.sessionError = errors.join('；');
      this.loadingSession = false;
    },

    /**
     * 加载近期用量；参数是天数；刷新当前用户汇总。
     */
    async loadRecent(days = 1) {
      const generation = ++recentRequestGeneration;
      this.loadingRecent = true;
      this.recentError = '';
      try {
        const response = await queryRecentModelUsage(days);
        if (generation === recentRequestGeneration) {
          this.recent = response.recent || null;
        }
      } catch (error) {
        if (generation === recentRequestGeneration) {
          this.recent = null;
          this.recentError = error instanceof Error ? error.message : '用量统计请求失败';
        }
      } finally {
        if (generation === recentRequestGeneration) {
          this.loadingRecent = false;
        }
      }
    },
  },
});
