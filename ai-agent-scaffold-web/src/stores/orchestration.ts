import { defineStore } from 'pinia';
import { cancelSessionSubagentTask, querySessionOrchestration, querySessionSubagentTask, streamSessionOrchestration } from '@/api/session';
import {
  isAsyncResultCurrent,
  mergeSubagentTaskDetail,
  resolveLoadedSubagentTaskDetail,
} from '@/domain/chat-orchestration-state';
import type { SessionOrchestrationSnapshot, SubagentTaskView } from '@/types/api';

let activeController: AbortController | undefined;
let reconnectTimer: number | undefined;
let connectionGeneration = 0;

export const useOrchestrationStore = defineStore('orchestration', {
  state: () => ({
    snapshots: {} as Record<string, SessionOrchestrationSnapshot>,
    connectedSessionId: '',
    loadingSessionIds: [] as string[],
    initializedSessionIds: [] as string[],
    errorMessage: '',
    cancellingTaskId: '',
    taskDetails: {} as Record<string, SubagentTaskView>,
    snapshotRevisions: {} as Record<string, number>,
    taskSnapshotRevisions: {} as Record<string, number>,
    taskRequestRevisions: {} as Record<string, number>,
  }),
  getters: {
    current: (state) => (sessionId: string) => state.snapshots[sessionId],
    initialized: (state) => (sessionId: string) => state.initializedSessionIds.includes(sessionId),
    task: (state) => (sessionId: string, taskId: string): SubagentTaskView | undefined =>
      state.taskDetails[taskId] || state.snapshots[sessionId]?.runs.flatMap((run) => run.tasks).find((task) => task.taskId === taskId),
  },
  actions: {
    applySnapshot(sessionId: string, value: SessionOrchestrationSnapshot) {
      if (!sessionId || value.sessionId !== sessionId) return;
      for (const task of value.runs.flatMap((run) => run.tasks)) {
        this.taskSnapshotRevisions[task.taskId] = (this.taskSnapshotRevisions[task.taskId] || 0) + 1;
        const cached = this.taskDetails[task.taskId];
        if (cached) this.taskDetails[task.taskId] = mergeSubagentTaskDetail(cached, task);
      }
      this.snapshots[sessionId] = value;
      this.snapshotRevisions[sessionId] = (this.snapshotRevisions[sessionId] || 0) + 1;
      if (!this.initializedSessionIds.includes(sessionId)) this.initializedSessionIds.push(sessionId);
      this.errorMessage = '';
    },
    async load(sessionId: string, quiet = false) {
      if (!sessionId) return false;
      if (!quiet && !this.loadingSessionIds.includes(sessionId)) this.loadingSessionIds.push(sessionId);
      const startedRevision = this.snapshotRevisions[sessionId] || 0;
      try {
        const value = await querySessionOrchestration(sessionId);
        if (isAsyncResultCurrent(startedRevision, this.snapshotRevisions[sessionId] || 0)) {
          this.applySnapshot(sessionId, value);
        }
        return true;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '编排状态读取失败';
        return false;
      } finally {
        this.loadingSessionIds = this.loadingSessionIds.filter((id) => id !== sessionId);
      }
    },
    connect(sessionId: string) {
      const generation = ++connectionGeneration;
      activeController?.abort();
      if (reconnectTimer) window.clearTimeout(reconnectTimer);
      reconnectTimer = undefined;
      this.connectedSessionId = sessionId;
      if (!sessionId) return;
      this.initializedSessionIds = this.initializedSessionIds.filter((id) => id !== sessionId);
      this.errorMessage = '';
      let hasFreshSnapshot = false;
      const isCurrentConnection = () => generation === connectionGeneration && this.connectedSessionId === sessionId;
      const connectOnce = async () => {
        if (!isCurrentConnection()) return;
        const controller = new AbortController(); activeController = controller;
        try {
          await streamSessionOrchestration(sessionId, hasFreshSnapshot ? this.snapshots[sessionId]?.version || '' : '', controller.signal, (value) => {
            if (isCurrentConnection()) {
              this.applySnapshot(sessionId, value);
              hasFreshSnapshot = true;
            }
          });
          if (!controller.signal.aborted && isCurrentConnection()) reconnectTimer = window.setTimeout(connectOnce, 500);
        } catch (error) {
          if (!controller.signal.aborted && isCurrentConnection()) {
            this.errorMessage = error instanceof Error ? error.message : '编排状态流已断开';
            reconnectTimer = window.setTimeout(connectOnce, 2000);
          }
        } finally {
          if (activeController === controller && !isCurrentConnection()) activeController = undefined;
        }
      };
      void (async () => {
        hasFreshSnapshot = await this.load(sessionId);
        if (generation !== connectionGeneration || !isCurrentConnection()) return;
        await connectOnce();
      })();
    },
    disconnect() {
      connectionGeneration += 1;
      this.connectedSessionId = '';
      activeController?.abort();
      if (reconnectTimer) window.clearTimeout(reconnectTimer);
      activeController = undefined;
      reconnectTimer = undefined;
    },
    async cancelTask(sessionId: string, taskId: string) {
      if (!sessionId || !taskId || this.cancellingTaskId) return false;
      this.cancellingTaskId = taskId; this.errorMessage = '';
      try {
        const result = await cancelSessionSubagentTask(sessionId, taskId);
        if (this.connectedSessionId === sessionId) this.connect(sessionId);
        else await this.load(sessionId, true);
        return result.cancelled;
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '取消子任务失败';
        return false;
      } finally { this.cancellingTaskId = ''; }
    },
    async loadTask(sessionId: string, taskId: string) {
      if (!sessionId || !taskId) return;
      const requestRevision = (this.taskRequestRevisions[taskId] || 0) + 1;
      this.taskRequestRevisions[taskId] = requestRevision;
      const startedSnapshotRevision = this.taskSnapshotRevisions[taskId] || 0;
      try {
        const loaded = await querySessionSubagentTask(sessionId, taskId);
        if (!isAsyncResultCurrent(requestRevision, this.taskRequestRevisions[taskId] || 0)) return;
        const currentSnapshotRevision = this.taskSnapshotRevisions[taskId] || 0;
        const latestSnapshot = this.snapshots[sessionId]?.runs.flatMap((run) => run.tasks)
          .find((task) => task.taskId === taskId);
        const resolved = resolveLoadedSubagentTaskDetail(
          loaded,
          latestSnapshot,
          !isAsyncResultCurrent(startedSnapshotRevision, currentSnapshotRevision),
        );
        const cached = this.taskDetails[taskId];
        this.taskDetails[taskId] = cached ? mergeSubagentTaskDetail(cached, resolved) : resolved;
      }
      catch (error) { this.errorMessage = error instanceof Error ? error.message : '子任务详情读取失败'; }
    },
  },
});
