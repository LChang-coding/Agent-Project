import { defineStore } from 'pinia';
import { cancelSessionSubagentTask, querySessionOrchestration, querySessionSubagentTask, streamSessionOrchestration } from '@/api/session';
import type { SessionOrchestrationSnapshot, SubagentTaskView } from '@/types/api';

let activeController: AbortController | undefined;
let reconnectTimer: number | undefined;

export const useOrchestrationStore = defineStore('orchestration', {
  state: () => ({
    snapshots: {} as Record<string, SessionOrchestrationSnapshot>,
    connectedSessionId: '',
    loadingSessionIds: [] as string[],
    errorMessage: '',
    cancellingTaskId: '',
    taskDetails: {} as Record<string, SubagentTaskView>,
  }),
  getters: {
    current: (state) => (sessionId: string) => state.snapshots[sessionId],
    task: (state) => (sessionId: string, taskId: string): SubagentTaskView | undefined =>
      state.taskDetails[taskId] || state.snapshots[sessionId]?.runs.flatMap((run) => run.tasks).find((task) => task.taskId === taskId),
  },
  actions: {
    async load(sessionId: string, quiet = false) {
      if (!sessionId) return;
      if (!quiet && !this.loadingSessionIds.includes(sessionId)) this.loadingSessionIds.push(sessionId);
      try {
        const value = await querySessionOrchestration(sessionId);
        this.snapshots[sessionId] = value;
        this.errorMessage = '';
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '编排状态读取失败';
      } finally {
        this.loadingSessionIds = this.loadingSessionIds.filter((id) => id !== sessionId);
      }
    },
    connect(sessionId: string) {
      activeController?.abort();
      if (reconnectTimer) window.clearTimeout(reconnectTimer);
      this.connectedSessionId = sessionId;
      if (!sessionId) return;
      void this.load(sessionId, true);
      const connectOnce = async () => {
        const controller = new AbortController(); activeController = controller;
        try {
          await streamSessionOrchestration(sessionId, this.snapshots[sessionId]?.version || '', controller.signal, (value) => {
            if (this.connectedSessionId === sessionId) this.snapshots[sessionId] = value;
          });
          if (!controller.signal.aborted && this.connectedSessionId === sessionId) reconnectTimer = window.setTimeout(connectOnce, 500);
        } catch (error) {
          if (!controller.signal.aborted && this.connectedSessionId === sessionId) {
            this.errorMessage = error instanceof Error ? error.message : '编排状态流已断开';
            reconnectTimer = window.setTimeout(connectOnce, 2000);
          }
        }
      };
      void connectOnce();
    },
    disconnect() {
      this.connectedSessionId = '';
      activeController?.abort();
      if (reconnectTimer) window.clearTimeout(reconnectTimer);
    },
    async cancelTask(sessionId: string, taskId: string) {
      if (!sessionId || !taskId || this.cancellingTaskId) return false;
      this.cancellingTaskId = taskId; this.errorMessage = '';
      try {
        const result = await cancelSessionSubagentTask(sessionId, taskId);
        await this.load(sessionId, true);
        return result.cancelled;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '取消子任务失败';
        return false;
      } finally { this.cancellingTaskId = ''; }
    },
    async loadTask(sessionId: string, taskId: string) {
      if (!sessionId || !taskId) return;
      try { this.taskDetails[taskId] = await querySessionSubagentTask(sessionId, taskId); }
      catch (error) { this.errorMessage = error instanceof Error ? error.message : '子任务详情读取失败'; }
    },
  },
});
