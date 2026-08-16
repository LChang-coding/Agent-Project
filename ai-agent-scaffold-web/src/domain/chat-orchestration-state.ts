import type { ChatMessage, SessionOrchestrationSnapshot, SubagentTaskView } from '@/types/api';

export type SubagentTaskTone = 'active' | 'error' | 'muted' | 'done';

/** WAIT_ALL 期间编排面板承载进度，不再显示空的主 Agent 占位消息。 */
export function shouldDisplayChatMessage(
  message: ChatMessage,
  orchestrationLocked: boolean,
  currentParentRunId?: string,
) {
  if (message.content) return true;
  if (message.status === 'done') return false;
  if (orchestrationLocked && message.role === 'assistant'
      && (!currentParentRunId || !message.runId || message.runId === currentParentRunId)) {
    return false;
  }
  return true;
}

/**
 * 用服务端最新页置换同一运行的乐观消息，同时保留已加载的更早历史。
 * 服务端 messageId 与浏览器临时 ID 不同，因此必须以 runId 识别已持久的乐观轮次。
 */
export function mergeAuthoritativeMessages(existing: ChatMessage[], authoritative: ChatMessage[]) {
  const authoritativeIds = new Set(authoritative.map((message) => message.id));
  const authoritativeRunIds = new Set(authoritative.map((message) => message.runId).filter(Boolean));
  const preserved = existing.filter((message) => {
    if (authoritativeIds.has(message.id)) return false;
    if (message.localOnly && message.runId && authoritativeRunIds.has(message.runId)) return false;
    return true;
  });
  return [...preserved, ...authoritative];
}

export function isConversationInteractionLocked(sending: boolean, orchestrationLocked: boolean) {
  return sending || orchestrationLocked;
}

export function isSessionSwitchBlocked(currentSessionId: string, targetSessionId: string, locked: boolean) {
  // 运行锁只限制当前会话内的第二次发送，不应阻断用户切换到其他会话。
  return false;
}

export function isCurrentSessionDeleteBlocked(currentSessionId: string, targetSessionId: string, locked: boolean) {
  return locked && Boolean(currentSessionId) && targetSessionId === currentSessionId;
}

export function subagentTaskFailed(task: Pick<SubagentTaskView, 'status' | 'errorCode'>) {
  return task.status === 'FAILED' || Boolean(task.errorCode);
}

export function subagentTaskTone(task: Pick<SubagentTaskView, 'status' | 'errorCode'>): SubagentTaskTone {
  if (subagentTaskFailed(task)) return 'error';
  if (task.status === 'READY' || task.status === 'RUNNING') return 'active';
  if (task.status === 'CANCELLED') return 'muted';
  return 'done';
}

export function subagentTaskLabel(
  task: Pick<SubagentTaskView, 'status' | 'callbackStatus' | 'errorCode'>,
) {
  if (subagentTaskFailed(task)) {
    return task.status === 'ACKED' || task.callbackStatus === 'DELIVERED' ? '执行失败 · 已回调' : '执行失败';
  }
  if (task.status === 'READY') return '等待执行';
  if (task.status === 'RUNNING') return '执行中';
  if (task.status === 'CANCELLED') return '已取消';
  return task.status === 'ACKED' || task.callbackStatus === 'DELIVERED' ? '已回调' : '等待汇总';
}

/** 侧边树只展示当前批次，避免多轮委派后把历史子 Agent 混在同一层。 */
export function currentRunTasks(snapshot?: Pick<SessionOrchestrationSnapshot, 'currentRunId' | 'runs'>) {
  if (!snapshot?.currentRunId || !snapshot.runs.length) return [];
  return snapshot.runs.find((run) => run.parentRunId === snapshot.currentRunId)?.tasks || [];
}

/** 快照状态为权威新值，详情接口返回的完整上下文在快照缺省时继续保留。 */
export function mergeSubagentTaskDetail(cached: SubagentTaskView, snapshot: SubagentTaskView): SubagentTaskView {
  return {
    ...cached,
    ...snapshot,
    fullContext: snapshot.fullContext ?? cached.fullContext,
  };
}

/** 异步读取只有在请求期间权威状态未推进时才可直接提交。 */
export function isAsyncResultCurrent(startedRevision: number, currentRevision: number) {
  return startedRevision === currentRevision;
}

/** 详情请求期间若收到新快照，以快照状态为准，同时保留详情接口返回的完整正文。 */
export function resolveLoadedSubagentTaskDetail(
  loaded: SubagentTaskView,
  latestSnapshot: SubagentTaskView | undefined,
  snapshotAdvanced: boolean,
) {
  return snapshotAdvanced && latestSnapshot ? mergeSubagentTaskDetail(loaded, latestSnapshot) : loaded;
}

/** 确认当前 WAIT_ALL 批次已有服务端持久化的非空汇总消息。 */
export function hasVisibleResumeFinalMessage(messages: ChatMessage[], runStartedAt?: string) {
  const startedAt = runStartedAt ? Date.parse(runStartedAt) : Number.NaN;
  return messages.some((message) => {
    if (message.role !== 'assistant' || message.localOnly || message.status !== 'done') return false;
    if (!message.runId?.startsWith('run_resume_') || !message.content.trim()) return false;
    if (!Number.isFinite(startedAt)) return true;
    const createdAt = Date.parse(message.createdAt);
    return Number.isFinite(createdAt) && createdAt >= startedAt;
  });
}

export interface RetryOptions {
  maxAttempts: number;
  delayMs: number;
}

/** 对最终消息这类幂等读取做有界指数退避，不用轮询子 Agent 状态。 */
export async function runWithRetry<T>(
  operation: () => Promise<T>,
  options: RetryOptions,
  wait: (delayMs: number) => Promise<void> = (delayMs) => new Promise((resolve) => window.setTimeout(resolve, delayMs)),
): Promise<T> {
  const maxAttempts = Math.max(1, Math.floor(options.maxAttempts));
  const initialDelay = Math.max(0, Math.floor(options.delayMs));
  let lastError: unknown;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      return await operation();
    } catch (error) {
      lastError = error;
      if (attempt === maxAttempts) break;
      await wait(initialDelay * (2 ** (attempt - 1)));
    }
  }
  throw lastError;
}

/** 合并快速到达的刷新请求；执行中到达的新版本至少会再触发一次。 */
export function createLatestRefresh(operation: () => Promise<void>) {
  let requestedRevision = 0;
  let completedRevision = 0;
  let activeRefresh: Promise<void> | null = null;

  const ensureRefresh = () => {
    if (!activeRefresh) {
      const execution = (async () => {
        while (completedRevision < requestedRevision) {
          const targetRevision = requestedRevision;
          await operation();
          completedRevision = targetRevision;
        }
      })();
      activeRefresh = execution.finally(() => {
        activeRefresh = null;
      });
    }
    return activeRefresh;
  };

  return {
    async request() {
      const targetRevision = ++requestedRevision;
      while (completedRevision < targetRevision) {
        await ensureRefresh();
      }
    },
  };
}
