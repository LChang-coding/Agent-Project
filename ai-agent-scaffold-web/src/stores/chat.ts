import { defineStore } from 'pinia';

import {
  cancelChatRun,
  createChatSession,
  queryAgentConfigs,
  sendChatMessage,
  sendChatMessageStream,
  steerChatRun,
} from '@/api/agent';
import { startIntelligentWorkflow, startStaticWorkflow, streamWorkflow } from '@/api/intelligent-workflow';
import { traceIdOfError } from '@/api/http';
import { queryWorkflowDetail, queryWorkflowNodeOptions, queryWorkflows } from '@/api/workflow';
import { createWorkflowRunState, reduceWorkflowEvent } from '@/domain/workflow-event-reducer';
import { workflowHistoryRunTargets } from '@/domain/workflow-history';
import {
  deleteChatSession,
  queryChatSessionMessages,
  queryChatSessions,
  querySessionRagSetting,
  updateSessionRagSetting,
} from '@/api/session';
import { useAuthStore } from '@/stores/auth';
import type {
  AiAgentConfig,
  ChatMessage,
  ChatRequest,
  LocalChatSession,
  RagInvocationMode,
  RunStreamEvent,
  SessionMessagePage,
  SessionRagEligibleBinding,
  SessionRagMode,
  SessionRagSetting,
  SessionShareResponse,
  WorkflowOption,
  WorkflowSummary,
} from '@/types/api';
import type { WorkflowRunViewState } from '@/types/intelligent-workflow';

interface TypewriterController {
  push: (content: string) => void;
  drain: () => Promise<void>;
  pause: () => void;
  resume: () => void;
  cancel: () => void;
}

interface ActiveChatRequest {
  generation: number;
  sessionId: string;
  runId: string;
  assistantMessageId: string;
  userMessageId: string;
  traceId: string;
  sessionTitle: string;
  controller: AbortController;
  typewriter?: TypewriterController;
  cancelRequested: boolean;
  streamSettled: boolean;
  runReady: Promise<void>;
  resolveRunReady: () => void;
}

let requestGeneration = 0;
let activeRequest: ActiveChatRequest | null = null;
let cancelPromise: Promise<void> | null = null;
let steerPromise: Promise<boolean> | null = null;
let sessionSwitchGeneration = 0;
let ragSettingGeneration = 0;
const SESSION_MESSAGE_PAGE_SIZE = 50;
const historyWorkflowControllers = new Map<string, AbortController>();

interface ChatState {
  agents: AiAgentConfig[];
  workflows: WorkflowSummary[];
  models: WorkflowOption[];
  activeSourceType: 'agent' | 'workflow';
  activeAgentId: string;
  activeWorkflowId: string;
  activeWorkflowKind: 'STATIC' | 'INTELLIGENT';
  activeModelCode: string;
  sessionId: string;
  messages: ChatMessage[];
  workflowRuns: Record<string, WorkflowRunViewState>;
  sessions: LocalChatSession[];
  loadingAgents: boolean;
  loadingWorkflows: boolean;
  sending: boolean;
  cancelling: boolean;
  steering: boolean;
  streaming: boolean;
  currentRunId: string;
  contextRevision: number;
  lastSettledRunId: string;
  lastTraceId: string;
  insightRefreshVersion: number;
  errorMessage: string;
  loadingSessions: boolean;
  deletingSessionId: string;
  nextBeforeSequence: number | null;
  hasMoreMessages: boolean;
  loadingEarlierMessages: boolean;
  loadingMessages: boolean;
  historyMessage: string;
  historyErrorMessage: string;
  ragEnabled: boolean;
  ragMode: SessionRagMode;
  ragInvocationMode: RagInvocationMode;
  ragSelectedBindingIds: string[];
  ragEligibleBindings: SessionRagEligibleBinding[];
  ragRevision?: number;
  ragBindingConfigured: boolean;
  ragSaving: boolean;
  ragMessage: string;
}

export const useChatStore = defineStore('chat', {
  state: (): ChatState => ({
    agents: [],
    workflows: [],
    models: [],
    activeSourceType: 'agent',
    activeAgentId: '',
    activeWorkflowId: '',
    activeWorkflowKind: 'STATIC',
    activeModelCode: 'deepseek-v4-flash',
    sessionId: '',
    messages: [],
    workflowRuns: {},
    sessions: [],
    loadingAgents: false,
    loadingWorkflows: false,
    sending: false,
    cancelling: false,
    steering: false,
    streaming: true,
    currentRunId: '',
    contextRevision: 0,
    lastSettledRunId: '',
    lastTraceId: '',
    insightRefreshVersion: 0,
    errorMessage: '',
    loadingSessions: false,
    deletingSessionId: '',
    nextBeforeSequence: null,
    hasMoreMessages: false,
    loadingEarlierMessages: false,
    loadingMessages: false,
    historyMessage: '',
    historyErrorMessage: '',
    ragEnabled: false,
    ragMode: 'OFF',
    ragInvocationMode: 'AUTO_CONTEXT',
    ragSelectedBindingIds: [],
    ragEligibleBindings: [],
    ragRevision: undefined,
    ragBindingConfigured: false,
    ragSaving: false,
    ragMessage: '创建或选择会话后可启用企业知识库检索。',
  }),
  getters: {
    activeAgent: (state) => state.agents.find((agent) => agent.agentId === state.activeAgentId),
    activeWorkflow: (state) => state.workflows.find((workflow) => workflow.workflowId === state.activeWorkflowId),
    hasSession: (state) => Boolean(state.sessionId),
  },
  actions: {
    /**
     * 加载智能体列表；无参数；返回后端已装配的智能体。
     */
    async loadAgents() {
      this.loadingAgents = true;
      this.loadingWorkflows = true;
      try {
        const [agents, workflows, options] = await Promise.all([
          queryAgentConfigs(),
          queryWorkflows(),
          queryWorkflowNodeOptions(),
        ]);
        this.agents = agents;
        this.workflows = workflows.filter((workflow) => workflow.publishedVersion > 0 && workflow.status === 'published');
        this.models = options.models;
        await this.loadSessions();
        if (!this.agents.some((agent) => agent.agentId === this.activeAgentId)) {
          this.activeAgentId = this.agents[0]?.agentId || '';
        }
        if (!this.workflows.some((workflow) => workflow.workflowId === this.activeWorkflowId)) {
          this.activeWorkflowId = this.workflows[0]?.workflowId || '';
        }
        if (this.activeSourceType === 'workflow' && !this.activeWorkflowId && this.agents.length > 0) {
          this.activeSourceType = 'agent';
        }
      } finally {
        this.loadingAgents = false;
        this.loadingWorkflows = false;
      }
    },

    /**
     * 切换智能体；参数是智能体 ID；会清空当前会话草稿。
     */
    async selectAgent(agentId: string) {
      await this.cancelActiveRun('切换智能体');
      this.activeSourceType = 'agent';
      this.activeAgentId = agentId;
      this.sessionId = '';
      this.messages = [];
      this.lastTraceId = '';
      this.resetRagSettingState();
      this.resetMessageHistoryState();
      this.errorMessage = '';
    },

    /**
     * 切换工作流；参数是工作流 ID；会清空当前会话草稿。
     */
    async selectWorkflow(workflowId: string) {
      await this.cancelActiveRun('切换工作流');
      this.activeSourceType = 'workflow';
      this.activeWorkflowId = workflowId;
      const detail = await queryWorkflowDetail(workflowId);
      this.activeWorkflowKind = detail.graph.workflowKind || 'STATIC';
      const workflow = this.workflows.find((item) => item.workflowId === workflowId);
      if (workflow?.defaultModelCode) {
        this.activeModelCode = workflow.defaultModelCode;
      }
      this.sessionId = '';
      this.messages = [];
      this.lastTraceId = '';
      this.resetRagSettingState();
      this.resetMessageHistoryState();
      this.errorMessage = '';
    },

    /**
     * 新建聊天会话；参数可指定智能体；返回新会话 ID。
     */
    async createSession(agentId?: string) {
      await this.cancelActiveRun('新建会话');
      const auth = useAuthStore();
      const result = await createChatSession(this.buildChatPayload(auth.userId, '', agentId));
      this.sessionId = result.sessionId;
      this.messages = [];
      this.lastTraceId = '';
      this.resetRagSettingState();
      this.resetMessageHistoryState();
      this.saveCurrentSession('新会话');
      await this.loadRagSetting(result.sessionId);
      return result.sessionId;
    },

    /**
     * 切换数据库会话；参数是会话 ID；从服务端恢复运行目标和有效消息。
     */
    async switchSession(sessionId: string) {
      if (sessionId === this.sessionId) {
        return;
      }
      await this.cancelActiveRun('切换会话');
      abortHistoryWorkflowStreams();
      const session = this.sessions.find((item) => item.sessionId === sessionId);
      if (!session) {
        return;
      }
      this.activeSourceType = session.sourceType || 'agent';
      if (this.activeSourceType === 'workflow') {
        this.activeWorkflowId = session.workflowId || session.agentId;
        this.activeModelCode = session.modelCode || this.activeModelCode;
        const detail = await queryWorkflowDetail(this.activeWorkflowId);
        this.activeWorkflowKind = detail.graph.workflowKind || 'STATIC';
      } else {
        this.activeAgentId = session.agentId;
        this.activeWorkflowKind = 'STATIC';
      }
      this.sessionId = session.sessionId;
      this.contextRevision = session.contextRevision || 0;
      this.ragEnabled = Boolean(session.ragEnabled);
      this.ragMode = session.ragMode || (session.ragEnabled ? 'AUTO' : 'OFF');
      this.ragInvocationMode = session.ragInvocationMode || 'AUTO_CONTEXT';
      this.ragSelectedBindingIds = [];
      this.ragEligibleBindings = [];
      this.ragRevision = session.ragRevision;
      this.ragBindingConfigured = false;
      this.ragSaving = false;
      this.ragMessage = this.ragEnabled ? '正在检查知识库绑定…' : 'RAG已关闭。';
      this.messages = [];
      this.lastTraceId = '';
      this.resetMessageHistoryState(false);
      await Promise.all([this.reloadSessionMessages(sessionId), this.loadRagSetting(sessionId)]);
    },

    /**
     * 重新读取当前数据库会话的有效消息；参数是会话 ID；不受同会话切换短路影响。
     */
    async reloadSessionMessages(sessionId: string, silent = false) {
      const generation = ++sessionSwitchGeneration;
      if (!silent) this.loadingMessages = true;
      if (!silent) {
        this.loadingEarlierMessages = false;
        this.nextBeforeSequence = null;
        this.hasMoreMessages = false;
        this.historyMessage = '';
        this.historyErrorMessage = '';
      }
      try {
        const page = await queryChatSessionMessages(sessionId, undefined, SESSION_MESSAGE_PAGE_SIZE);
        if (generation !== sessionSwitchGeneration || this.sessionId !== sessionId) {
          return;
        }
        const latestMessages = page.items.map(toChatMessage);
        if (silent) {
          const latestIds = new Set(latestMessages.map((message) => message.id));
          const earlierMessages = this.messages.filter((message) => !latestIds.has(message.id));
          this.messages = [...earlierMessages, ...latestMessages];
        } else {
          this.messages = latestMessages;
        }
        this.lastTraceId = [...this.messages].reverse().find((message) => message.traceId)?.traceId || '';
        if (!silent) {
          this.nextBeforeSequence = page.hasMore ? page.nextBeforeSequence ?? null : null;
          this.hasMoreMessages = page.hasMore && this.nextBeforeSequence !== null;
        }
        this.errorMessage = '';
        if (this.activeSourceType === 'workflow') {
          // 消息先恢复；节点事件在后台重放，避免活动 Run 阻塞会话页面可用性。
          void this.restoreIntelligentWorkflowHistory(sessionId, this.messages);
        }
      } catch (error) {
        if (!silent && generation === sessionSwitchGeneration && this.sessionId === sessionId) {
          this.errorMessage = error instanceof Error ? error.message : '读取会话消息失败';
        }
      } finally {
        if (!silent && generation === sessionSwitchGeneration && this.sessionId === sessionId) {
          this.loadingMessages = false;
        }
      }
    },

    /** 根据历史消息保存的 runId/traceId 重放节点事件，页面刷新后仍可展开执行面板。 */
    async restoreIntelligentWorkflowHistory(sessionId: string, messages: ChatMessage[]) {
      const targets = workflowHistoryRunTargets(messages);
      await Promise.allSettled(targets.map(async ({ runId, traceId }) => {
        if (this.workflowRuns[runId]) return;
        let assistantMessage = this.messages.find((message) => message.role === 'assistant' && message.runId === runId);
        const createdPlaceholder = !assistantMessage;
        if (!assistantMessage) {
          const source = this.messages.find((message) => message.runId === runId);
          assistantMessage = {
            id: `workflow-run-${runId}`,
            runId,
            role: 'assistant',
            content: '',
            createdAt: source?.createdAt || new Date().toISOString(),
            traceId,
            status: 'streaming',
          };
          this.messages.push(assistantMessage);
        }
        this.workflowRuns[runId] = createWorkflowRunState(runId, traceId);
        const controller = new AbortController();
        historyWorkflowControllers.set(runId, controller);
        try {
          let attempts = 0;
          while (this.sessionId === sessionId && this.workflowRuns[runId]?.status === 'running') {
            try {
              await streamWorkflow(runId, traceId, this.workflowRuns[runId].lastSequence, {
                signal: controller.signal,
                onEvent: (event) => {
                  if (this.sessionId !== sessionId) return;
                  const current = this.workflowRuns[runId];
                  if (current) {
                    const next = reduceWorkflowEvent(current, event);
                    this.workflowRuns[runId] = next;
                    if (assistantMessage) {
                      assistantMessage.content = next.finalAnswer;
                      assistantMessage.status = next.status === 'running' ? 'streaming'
                        : next.status === 'completed' ? 'done'
                          : next.status === 'cancelled' ? 'canceled' : 'error';
                    }
                  }
                },
              });
              attempts = 0;
            } catch (error) {
              if (controller.signal.aborted || ++attempts > 3) throw error;
              await wait(250 * attempts);
            }
          }
        } catch {
          // 旧静态工作流没有 workflow-event-v1；保留消息，但不伪造节点面板。
          if (this.workflowRuns[runId]?.status === 'running') {
            delete this.workflowRuns[runId];
            if (createdPlaceholder) {
              this.messages = this.messages.filter((message) => message.id !== `workflow-run-${runId}`);
            }
          }
        } finally {
          if (historyWorkflowControllers.get(runId) === controller) historyWorkflowControllers.delete(runId);
        }
      }));
    },

    /**
     * 加载当前会话更早一页消息；无重复请求，旧会话响应不会回写。
     */
    async loadEarlierMessages() {
      const sessionId = this.sessionId;
      const beforeSequence = this.nextBeforeSequence;
      if (!sessionId || !this.hasMoreMessages || beforeSequence === null || this.loadingEarlierMessages) {
        return 0;
      }
      const generation = sessionSwitchGeneration;
      this.loadingEarlierMessages = true;
      this.historyMessage = '';
      this.historyErrorMessage = '';
      try {
        const page = await queryChatSessionMessages(sessionId, beforeSequence, SESSION_MESSAGE_PAGE_SIZE);
        if (generation !== sessionSwitchGeneration || this.sessionId !== sessionId) {
          return 0;
        }
        const existingIds = new Set(this.messages.map((message) => message.id));
        const earlierMessages = page.items.map(toChatMessage).filter((message) => !existingIds.has(message.id));
        this.messages = [...earlierMessages, ...this.messages];
        if (this.activeSourceType === 'workflow') {
          void this.restoreIntelligentWorkflowHistory(sessionId, earlierMessages);
        }
        this.nextBeforeSequence = page.hasMore ? page.nextBeforeSequence ?? null : null;
        this.hasMoreMessages = page.hasMore && this.nextBeforeSequence !== null;
        this.historyMessage = earlierMessages.length > 0
          ? `已加载 ${earlierMessages.length} 条更早消息。`
          : '这一页没有新的有效消息。';
        return earlierMessages.length;
      } catch (error) {
        if (generation === sessionSwitchGeneration && this.sessionId === sessionId) {
          this.historyErrorMessage = error instanceof Error ? error.message : '更早消息加载失败';
        }
        throw error;
      } finally {
        if (generation === sessionSwitchGeneration && this.sessionId === sessionId) {
          this.loadingEarlierMessages = false;
        }
      }
    },

    /** 清空消息分页游标和反馈；用于新会话或运行目标切换。 */
    resetMessageHistoryState(invalidateRequests = true) {
      if (invalidateRequests) {
        sessionSwitchGeneration += 1;
        abortHistoryWorkflowStreams();
      }
      this.nextBeforeSequence = null;
      this.hasMoreMessages = false;
      this.loadingEarlierMessages = false;
      this.loadingMessages = false;
      this.historyMessage = '';
      this.historyErrorMessage = '';
    },

    /**
     * 接收服务端复制导入会话；参数是导入响应；写入当前用户本地展示索引并打开会话。
     */
    async acceptImportedSession(imported: SessionShareResponse) {
      if (!imported.sessionId) {
        throw new Error('导入结果缺少会话ID');
      }
      if (this.agents.length === 0 && this.workflows.length === 0) {
        await this.loadAgents();
      }
      const workflowId = imported.workflowId || (imported.sourceType === 'workflow' ? imported.agentId : undefined);
      const workflow = this.workflows.find((item) => item.workflowId === workflowId);
      const sourceType: 'agent' | 'workflow' = imported.sourceType || 'agent';
      this.activeSourceType = sourceType;
      if (sourceType === 'workflow') {
        this.activeWorkflowId = workflowId || '';
        this.activeModelCode = imported.modelCode || workflow?.defaultModelCode || this.activeModelCode;
      } else {
        this.activeAgentId = imported.agentId || this.activeAgentId;
      }
      await this.loadSessions();
      const importedSession = this.sessions.find((session) => session.sessionId === imported.sessionId);
      if (importedSession) {
        importedSession.sourceType = sourceType;
        importedSession.agentId = sourceType === 'workflow' ? workflowId || importedSession.agentId : imported.agentId || importedSession.agentId;
        importedSession.workflowId = sourceType === 'workflow' ? workflowId : undefined;
        importedSession.workflowName = sourceType === 'workflow' ? imported.agentName : undefined;
        importedSession.workflowVersion = sourceType === 'workflow' ? imported.workflowVersion : undefined;
        importedSession.modelCode = sourceType === 'workflow' ? imported.modelCode : undefined;
      }
      await this.switchSession(imported.sessionId);
    },

    /**
     * 发送聊天消息；参数是用户输入；根据开关返回流式或完整回复。
     */
    async send(message: string, requestedRunId = '', attachmentIds: string[] = []) {
      if (!message.trim() || !this.hasActiveTarget() || this.sending) {
        return;
      }

      const auth = useAuthStore();
      if (this.activeSourceType === 'workflow' && !this.sessionId) {
        const created = await createChatSession(this.buildChatPayload(auth.userId, ''));
        this.sessionId = created.sessionId;
        this.saveCurrentSession(message.trim());
      }
      const effectiveRunId = requestedRunId || createRunId();
      const userMessage: ChatMessage = {
        id: createId(),
        runId: effectiveRunId,
        role: 'user',
        content: message.trim(),
        createdAt: new Date().toISOString(),
        status: 'done',
      };
      const assistantMessage: ChatMessage = {
        id: createId(),
        runId: effectiveRunId,
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
        status: this.streaming ? 'streaming' : 'sending',
      };

      this.messages.push(userMessage, assistantMessage);
      const assistantMessageId = assistantMessage.id;
      const controller = new AbortController();
      let resolveRunReady: () => void = () => {};
      const runReady = new Promise<void>((resolve) => {
        resolveRunReady = resolve;
      });
      const effectiveAttachmentIds = [...new Set(attachmentIds.filter(Boolean))];
      const request: ActiveChatRequest = {
        generation: ++requestGeneration,
        sessionId: this.sessionId,
        runId: effectiveRunId,
        assistantMessageId,
        userMessageId: userMessage.id,
        traceId: '',
        sessionTitle: userMessage.content,
        controller,
        cancelRequested: false,
        streamSettled: false,
        runReady,
        resolveRunReady,
      };
      activeRequest = request;
      this.sending = true;
      this.cancelling = false;
      this.currentRunId = effectiveRunId;
      this.errorMessage = '';
      this.saveCurrentSession(userMessage.content);

      try {
        if (this.activeSourceType === 'workflow') {
          const workflow = this.activeWorkflow;
          const payload = {
            workflowId: this.activeWorkflowId,
            workflowVersion: workflow?.publishedVersion,
            modelCode: this.activeModelCode,
            sessionId: this.sessionId,
            message: userMessage.content,
            requestedRunId: effectiveRunId,
            attachmentIds: effectiveAttachmentIds.length > 0 ? effectiveAttachmentIds : undefined,
          };
          const started = this.activeWorkflowKind === 'INTELLIGENT'
            ? await startIntelligentWorkflow(payload)
            : await startStaticWorkflow(payload);
          if (!this.isRequestCurrent(request)) return;
          request.runId = started.runId;
          this.currentRunId = started.runId;
          userMessage.runId = started.runId;
          assistantMessage.runId = started.runId;
          this.bindTrace(request, started.traceId);
          request.resolveRunReady();
          this.workflowRuns[started.runId] = createWorkflowRunState(started.runId, started.traceId);
          let reconnectAttempt = 0;
          while (this.workflowRuns[started.runId].status === 'running') {
            try {
              await streamWorkflow(started.runId, started.traceId,
                this.workflowRuns[started.runId].lastSequence, {
                  signal: controller.signal,
                  onEvent: (event) => {
                    if (!this.isRequestCurrent(request)) return;
                    const current = this.workflowRuns[started.runId];
                    const next = reduceWorkflowEvent(current, event);
                    this.workflowRuns[started.runId] = next;
                    this.replaceMessageContent(assistantMessageId, next.finalAnswer);
                    if (next.status === 'failed') this.errorMessage = next.errorMessage;
                  },
                });
              reconnectAttempt = 0;
            } catch (streamError) {
              if (controller.signal.aborted || ++reconnectAttempt > 3) throw streamError;
              await wait(250 * reconnectAttempt);
            }
          }
          if (!this.isRequestWritable(request)) return;
          const settled = this.workflowRuns[started.runId];
          this.updateMessageStatus(assistantMessageId,
            settled.status === 'completed' ? 'done' : settled.status === 'cancelled' ? 'canceled' : 'error');
          if (!assistantMessage.content && settled.status !== 'completed') {
            this.replaceMessageContent(assistantMessageId, settled.errorMessage || '工作流未生成最终回答。');
          }
          this.saveCurrentSession(userMessage.content);
          return;
        }
        if (this.streaming) {
          const typewriter = createTypewriter((content) => {
            if (this.isRequestWritable(request)) {
              this.appendMessageContent(assistantMessageId, content);
            }
          });
          request.typewriter = typewriter;
          let streamSnapshot = '';
          await sendChatMessageStream(
            this.buildChatPayload(auth.userId, userMessage.content, undefined, effectiveRunId, effectiveAttachmentIds),
            {
              onTrace: (traceId) => {
                this.bindTrace(request, traceId);
              },
              onSession: (sessionId) => {
                if (!this.isRequestCurrent(request) || request.cancelRequested) {
                  return;
                }
                request.sessionId = sessionId;
                this.sessionId = sessionId;
                this.saveCurrentSession(userMessage.content);
              },
              onRun: (run) => {
                this.bindRun(request, run);
              },
              onChunk: (content) => {
                if (!this.isRequestCurrent(request)) {
                  return;
                }
                const result = resolveStreamChunk(streamSnapshot, content);
                streamSnapshot = result.snapshot;
                if (result.delta) {
                  if (request.cancelRequested && !this.steering) {
                    return;
                  }
                  typewriter.push(result.delta);
                }
              },
              onError: (error) => {
                if (this.isRequestWritable(request)) {
                  this.errorMessage = error;
                }
              },
              signal: controller.signal,
            },
          );
          await typewriter.drain();
          if (!this.isRequestWritable(request)) {
            return;
          }
          this.updateMessageStatus(assistantMessageId, 'done');
          this.saveCurrentSession(userMessage.content);
          return;
        }

        const response = await sendChatMessage(
          this.buildChatPayload(auth.userId, userMessage.content, undefined, effectiveRunId, effectiveAttachmentIds),
          controller.signal,
        );
        if (!this.isRequestWritable(request)) {
          return;
        }
        request.sessionId = response.sessionId;
        request.runId = response.runId;
        this.bindTrace(request, response.traceId || '');
        this.currentRunId = response.runId;
        this.contextRevision = response.contextRevision;
        this.sessionId = response.sessionId;
        this.replaceMessageContent(assistantMessageId, response.content);
        this.updateMessageStatus(assistantMessageId, 'done');
        this.saveCurrentSession(userMessage.content);
      } catch (error) {
        this.bindTrace(request, traceIdOfError(error));
        if (isAbortError(error) || request.cancelRequested || !this.isRequestCurrent(request)) {
          return;
        }
        this.updateMessageStatus(assistantMessageId, 'error');
        this.replaceMessageContent(assistantMessageId, '这次对话请求失败了，请检查后端服务、令牌状态或模型配置。');
        this.errorMessage = error instanceof Error ? error.message : '发送失败';
        this.saveCurrentSession(userMessage.content);
      } finally {
        request.streamSettled = true;
        if (this.isRequestCurrent(request) && !request.cancelRequested) {
          this.markInsightRefresh(request.runId);
          request.typewriter?.cancel();
          activeRequest = null;
          this.sending = false;
          this.cancelling = false;
          this.currentRunId = '';
        }
      }
    },

    /**
     * 取消当前运行；参数是取消原因；先通知服务端再中断本地流。
     */
    async cancelActiveRun(reason = '用户主动取消'): Promise<void> {
      if (steerPromise) {
        await steerPromise;
        return this.cancelActiveRun(reason);
      }
      if (cancelPromise) {
        return cancelPromise;
      }
      const request = activeRequest;
      if (!request) {
        return;
      }
      request.cancelRequested = true;
      request.typewriter?.cancel();
      this.cancelling = true;
      cancelPromise = (async () => {
        let cancelledOnServer = false;
        try {
          if (!request.runId && this.streaming) {
            await Promise.race([request.runReady, wait(1_500)]);
          }
          if (request.runId) {
            const result = await cancelChatRun(request.runId, reason);
            this.contextRevision = result.contextRevision;
            cancelledOnServer = true;
          }
        } catch (error) {
          this.errorMessage = error instanceof Error ? `取消请求失败：${error.message}` : '取消请求失败';
        } finally {
          this.markInsightRefresh(request.runId);
          request.controller.abort();
          request.typewriter?.cancel();
          requestGeneration += 1;
          if (this.sessionId === request.sessionId || !request.sessionId) {
            const message = this.messages.find((item) => item.id === request.assistantMessageId);
            if (message) {
              message.status = 'canceled';
              if (!message.content) {
                message.content = '已取消本次生成。';
              }
            }
            this.saveCurrentSession(request.sessionTitle);
          }
          if (activeRequest === request) {
            activeRequest = null;
          }
          this.sending = false;
          this.cancelling = false;
          this.currentRunId = '';
          if (cancelledOnServer && request.sessionId && this.sessionId === request.sessionId) {
            await this.reloadSessionMessages(request.sessionId);
          }
        }
      })();
      try {
        await cancelPromise;
      } finally {
        cancelPromise = null;
      }
    },

    /**
     * 引导当前运行；参数是新指令；返回是否成功启动后继运行。
     */
    async steerActiveRun(instruction: string) {
      const normalizedInstruction = instruction.trim();
      if (!normalizedInstruction) {
        this.errorMessage = '请先输入引导指令';
        return false;
      }
      if (cancelPromise || this.cancelling) {
        this.errorMessage = '当前运行正在取消，无法再引导';
        return false;
      }
      if (steerPromise) {
        return steerPromise;
      }
      const request = activeRequest;
      if (!request || !request.runId || !this.isRequestCurrent(request)) {
        this.errorMessage = '运行尚未建立或已结束，无法引导';
        return false;
      }

      request.cancelRequested = true;
      this.steering = true;
      request.typewriter?.pause();
      this.errorMessage = '';
      steerPromise = (async () => {
        try {
          const successor = await steerChatRun(request.runId, normalizedInstruction);
          if (!this.isRequestCurrent(request) || this.sessionId !== request.sessionId) {
            throw new Error('会话已变更，未在当前界面启动引导后继');
          }

          request.controller.abort();
          request.typewriter?.cancel();
          const previousAssistant = this.messages.find((item) => item.id === request.assistantMessageId);
          if (previousAssistant) {
            previousAssistant.status = 'superseded';
            if (!previousAssistant.content) {
              previousAssistant.content = '已由新的引导指令替代。';
            }
          }
          this.contextRevision = successor.contextRevision;
          this.currentRunId = successor.runId;
          this.saveCurrentSession(request.sessionTitle);

          requestGeneration += 1;
          if (activeRequest === request) {
            activeRequest = null;
          }
          this.sending = false;
          this.cancelling = false;
          await this.reloadSessionMessages(request.sessionId);
          void this.send(normalizedInstruction, successor.runId);
          return true;
        } catch (error) {
          this.errorMessage = error instanceof Error ? `引导失败：${error.message}` : '引导失败';
          if (this.isRequestCurrent(request)) {
            request.cancelRequested = false;
            const previousAssistant = this.messages.find((item) => item.id === request.assistantMessageId);
            if (request.streamSettled) {
              this.markInsightRefresh(request.runId);
              request.typewriter?.cancel();
              activeRequest = null;
              this.sending = false;
              this.currentRunId = '';
              if (previousAssistant) {
                previousAssistant.status = previousAssistant.content ? 'done' : 'error';
                if (!previousAssistant.content) {
                  previousAssistant.content = '原运行已结束，引导未生效。';
                }
              }
              this.saveCurrentSession(request.sessionTitle);
            } else {
              if (previousAssistant) {
                previousAssistant.status = 'streaming';
              }
              request.typewriter?.resume();
            }
          }
          return false;
        } finally {
          this.steering = false;
        }
      })();
      try {
        return await steerPromise;
      } finally {
        steerPromise = null;
      }
    },

    /**
     * 绑定 SSE 运行信息；参数是请求上下文和运行事件；只更新当前请求。
     */
    bindRun(request: ActiveChatRequest, run: RunStreamEvent) {
      if (!this.isRequestCurrent(request) || !run.runId) {
        return;
      }
      request.runId = run.runId;
      this.currentRunId = run.runId;
      this.contextRevision = run.contextRevision;
      this.bindTrace(request, run.traceId || '');
      request.resolveRunReady();
    },

    /**
     * 绑定请求链路号；参数是当前请求和链路号；同步到本轮消息及顶部状态。
     */
    bindTrace(request: ActiveChatRequest, traceId: string) {
      if (!traceId || !this.isRequestCurrent(request)) {
        return;
      }
      request.traceId = traceId;
      this.lastTraceId = traceId;
      const userMessage = this.messages.find((message) => message.id === request.userMessageId);
      const assistantMessage = this.messages.find((message) => message.id === request.assistantMessageId);
      if (userMessage) {
        userMessage.traceId = traceId;
      }
      if (assistantMessage) {
        assistantMessage.traceId = traceId;
      }
    },

    /**
     * 判断请求是否仍为当前请求；参数是请求上下文；返回代次是否匹配。
     */
    isRequestCurrent(request: ActiveChatRequest) {
      return activeRequest === request && request.generation === requestGeneration;
    },

    /**
     * 判断异步回调是否可写入；参数是请求上下文；返回会话、运行和代次是否一致。
     */
    isRequestWritable(request: ActiveChatRequest) {
      if (!this.isRequestCurrent(request) || request.cancelRequested) {
        return false;
      }
      if (request.sessionId && this.sessionId !== request.sessionId) {
        return false;
      }
      return !request.runId || this.currentRunId === request.runId;
    },

    /**
     * 追加消息内容；参数是消息 ID 和内容；通过响应式数组更新页面。
     */
    appendMessageContent(messageId: string, content: string) {
      const message = this.messages.find((item) => item.id === messageId);
      if (message) {
        message.content += content;
      }
    },

    /**
     * 替换消息内容；参数是消息 ID 和内容；通过响应式数组更新页面。
     */
    replaceMessageContent(messageId: string, content: string) {
      const message = this.messages.find((item) => item.id === messageId);
      if (message) {
        message.content = content;
      }
    },

    /**
     * 更新消息状态；参数是消息 ID 和状态；通过响应式数组更新页面。
     */
    updateMessageStatus(messageId: string, status: ChatMessage['status']) {
      const message = this.messages.find((item) => item.id === messageId);
      if (message) {
        message.status = status;
      }
    },

    /**
     * 标记运行统计可刷新；参数是已收口运行ID；通知页面重新拉取洞察。
     */
    markInsightRefresh(runId: string) {
      this.lastSettledRunId = runId;
      this.insightRefreshVersion += 1;
    },

    /**
     * 加载数据库会话列表；无参数；覆盖浏览器内的会话摘要。
     */
    async loadSessions() {
      this.loadingSessions = true;
      try {
        const rows = [];
        let cursor: string | undefined;
        do {
          const page = await queryChatSessions(cursor);
          rows.push(...page.items);
          cursor = page.hasMore ? page.nextCursor : undefined;
        } while (cursor);
        this.sessions = rows.map((session) => {
          const sourceType: 'agent' | 'workflow' = session.sourceType === 'workflow' ? 'workflow' : 'agent';
          const workflow = sourceType === 'workflow'
            ? this.workflows.find((item) => item.workflowId === session.agentId)
            : undefined;
          return {
            sessionId: session.sessionId,
            agentId: session.agentId,
            agentName: workflow?.workflowName || session.agentName,
            sourceType,
            workflowId: sourceType === 'workflow' ? session.agentId : undefined,
            workflowName: sourceType === 'workflow' ? workflow?.workflowName : undefined,
            workflowVersion: sourceType === 'workflow' ? session.workflowVersion : undefined,
            modelCode: sourceType === 'workflow' ? session.modelCode || workflow?.defaultModelCode : undefined,
            title: session.title,
            updatedAt: session.lastMessageTime,
            contextRevision: session.contextRevision,
            ragEnabled: Boolean(session.ragEnabled),
            ragMode: session.ragMode || (session.ragEnabled ? 'AUTO' : 'OFF'),
            ragInvocationMode: session.ragInvocationMode || 'AUTO_CONTEXT',
            ragRevision: session.ragRevision,
          } satisfies LocalChatSession;
        });
      } finally {
        this.loadingSessions = false;
      }
    },

    /**
     * 删除数据库会话；参数是会话ID；取消当前运行后移除列表并选择下一会话。
     */
    async deleteSession(sessionId: string) {
      if (this.deletingSessionId) {
        return;
      }
      this.deletingSessionId = sessionId;
      this.errorMessage = '';
      try {
        if (sessionId === this.sessionId) {
          await this.cancelActiveRun('删除会话');
        }
        await deleteChatSession(sessionId);
        const remaining = this.sessions.filter((item) => item.sessionId !== sessionId);
        this.sessions = remaining;
        if (sessionId === this.sessionId) {
          this.sessionId = '';
          this.messages = [];
          this.resetMessageHistoryState();
          this.currentRunId = '';
          this.resetRagSettingState();
          const next = remaining.find((item) => item.sourceType === this.activeSourceType);
          if (next) {
            await this.switchSession(next.sessionId);
          }
        }
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '删除会话失败';
        throw error;
      } finally {
        this.deletingSessionId = '';
      }
    },

    /**
     * 保存当前会话摘要；参数是默认标题；只更新内存并异步刷新数据库列表。
     */
    saveCurrentSession(defaultTitle: string) {
      if (!this.sessionId || !this.hasActiveTarget()) {
        return;
      }
      const activeAgent = this.activeAgent;
      const activeWorkflow = this.activeWorkflow;
      const existingSession = this.sessions.find((item) => item.sessionId === this.sessionId);
      const runtimeName = this.activeSourceType === 'workflow'
        ? activeWorkflow?.workflowName || this.activeWorkflowId
        : activeAgent?.agentName || this.activeAgentId;
      const stableTitle = existingSession?.title && existingSession.title !== '新会话'
        ? existingSession.title
        : '';
      const title = stableTitle || firstUserMessage(this.messages) || defaultTitle || runtimeName || '新会话';
      const session: LocalChatSession = {
        sessionId: this.sessionId,
        agentId: this.activeSourceType === 'workflow' ? this.activeWorkflowId : this.activeAgentId,
        agentName: runtimeName,
        sourceType: this.activeSourceType,
        workflowId: this.activeSourceType === 'workflow' ? this.activeWorkflowId : undefined,
        workflowName: this.activeSourceType === 'workflow' ? runtimeName : undefined,
        workflowVersion: this.activeSourceType === 'workflow' ? activeWorkflow?.publishedVersion : undefined,
        modelCode: this.activeSourceType === 'workflow' ? this.activeModelCode : undefined,
        title: compactTitle(title),
        updatedAt: new Date().toISOString(),
        contextRevision: this.contextRevision,
        ragEnabled: this.ragEnabled,
        ragMode: this.ragMode,
        ragInvocationMode: this.ragInvocationMode,
        ragRevision: this.ragRevision,
      };
      const nextSessions = [session, ...this.sessions.filter((item) => item.sessionId !== session.sessionId)];
      this.sessions = nextSessions;
    },

    /** 读取当前会话RAG设置和绑定状态。 */
    async loadRagSetting(sessionId?: string) {
      const targetSessionId = sessionId || this.sessionId;
      const generation = ++ragSettingGeneration;
      if (!targetSessionId) {
        this.ragEnabled = false;
        this.ragMode = 'OFF';
        this.ragInvocationMode = 'AUTO_CONTEXT';
        this.ragSelectedBindingIds = [];
        this.ragEligibleBindings = [];
        this.ragRevision = undefined;
        this.ragBindingConfigured = false;
        this.ragSaving = false;
        this.ragMessage = '创建或选择会话后可启用企业知识库检索。';
        return;
      }
      try {
        const setting = await querySessionRagSetting(targetSessionId);
        if (generation !== ragSettingGeneration || this.sessionId !== targetSessionId) return;
        this.applyRagSetting(setting, targetSessionId);
      } catch (error) {
        if (generation === ragSettingGeneration && this.sessionId === targetSessionId) {
          this.ragMessage = error instanceof Error ? error.message : 'RAG设置读取失败';
        }
      }
    },

    /** 切换并持久化当前会话RAG策略；失败时完整恢复此前快照。 */
    async setRagSetting(mode: SessionRagMode, selectedBindingIds: string[] = []) {
      if (!this.sessionId || this.ragSaving || this.sending) return;
      const capturedSessionId = this.sessionId;
      const generation = ++ragSettingGeneration;
      const previous = {
        ragEnabled: this.ragEnabled,
        ragMode: this.ragMode,
        ragInvocationMode: this.ragInvocationMode,
        ragSelectedBindingIds: [...this.ragSelectedBindingIds],
        ragEligibleBindings: [...this.ragEligibleBindings],
        ragRevision: this.ragRevision,
        ragBindingConfigured: this.ragBindingConfigured,
        ragMessage: this.ragMessage,
      };
      const normalizedIds = mode === 'MANUAL' ? [...new Set(selectedBindingIds)] : [];
      this.ragEnabled = mode !== 'OFF';
      this.ragMode = mode;
      this.ragSelectedBindingIds = normalizedIds;
      this.ragSaving = true;
      this.ragMessage = mode === 'OFF'
        ? '正在关闭企业知识库检索…'
        : mode === 'AUTO' ? '正在启用自动知识库检索…' : '正在保存指定知识库…';
      try {
        const setting = await updateSessionRagSetting(capturedSessionId, {
          mode,
          invocationMode: this.ragInvocationMode,
          selectedBindingIds: normalizedIds,
          expectedRevision: previous.ragRevision,
        });
        if (generation !== ragSettingGeneration || this.sessionId !== capturedSessionId) return;
        this.applyRagSetting(setting, capturedSessionId);
      } catch (error) {
        if (generation === ragSettingGeneration && this.sessionId === capturedSessionId) {
          this.ragEnabled = previous.ragEnabled;
          this.ragMode = previous.ragMode;
          this.ragInvocationMode = previous.ragInvocationMode;
          this.ragSelectedBindingIds = previous.ragSelectedBindingIds;
          this.ragEligibleBindings = previous.ragEligibleBindings;
          this.ragRevision = previous.ragRevision;
          this.ragBindingConfigured = previous.ragBindingConfigured;
          this.ragMessage = error instanceof Error ? error.message : 'RAG设置保存失败';
        }
        throw error;
      } finally {
        if (generation === ragSettingGeneration && this.sessionId === capturedSessionId) {
          this.ragSaving = false;
        }
      }
    },

    /** 将新旧版本RAG响应规范化后原子写入当前会话。 */
    applyRagSetting(setting: SessionRagSetting, sessionId: string) {
      const mode = setting.mode || (setting.enabled ? 'AUTO' : 'OFF');
      this.ragMode = mode;
      this.ragInvocationMode = setting.invocationMode || 'AUTO_CONTEXT';
      this.ragEnabled = mode !== 'OFF';
      this.ragSelectedBindingIds = mode === 'MANUAL' ? [...(setting.selectedBindingIds || [])] : [];
      this.ragEligibleBindings = [...(setting.eligibleBindings || [])]
        .sort((left, right) => left.priority - right.priority);
      this.ragRevision = setting.revision;
      this.ragBindingConfigured = setting.bindingConfigured;
      this.ragMessage = setting.message;
      const session = this.sessions.find((item) => item.sessionId === sessionId);
      if (session) {
        session.ragEnabled = this.ragEnabled;
        session.ragMode = mode;
        session.ragInvocationMode = this.ragInvocationMode;
        session.ragRevision = setting.revision;
      }
    },

    /** 清空会话级RAG视图并使在途读写响应失效。 */
    resetRagSettingState() {
      ragSettingGeneration += 1;
      this.ragEnabled = false;
      this.ragMode = 'OFF';
      this.ragInvocationMode = 'AUTO_CONTEXT';
      this.ragSelectedBindingIds = [];
      this.ragEligibleBindings = [];
      this.ragRevision = undefined;
      this.ragBindingConfigured = false;
      this.ragSaving = false;
      this.ragMessage = '创建或选择会话后可启用企业知识库检索。';
    },

    /** 切换独立的RAG调用方式；RAG关闭时不允许修改。 */
    async setRagInvocationMode(mode: RagInvocationMode) {
      if (!this.sessionId || !this.ragEnabled || this.ragSaving || this.sending) return;
      const sessionId = this.sessionId;
      const previous = this.ragInvocationMode;
      this.ragInvocationMode = mode;
      this.ragSaving = true;
      try {
        const setting = await updateSessionRagSetting(sessionId, {
          mode: this.ragMode,
          invocationMode: mode,
          selectedBindingIds: this.ragMode === 'MANUAL' ? [...this.ragSelectedBindingIds] : [],
          expectedRevision: this.ragRevision,
        });
        if (this.sessionId === sessionId) this.applyRagSetting(setting, sessionId);
      } catch (error) {
        if (this.sessionId === sessionId) {
          this.ragInvocationMode = previous;
          this.ragMessage = error instanceof Error ? error.message : 'RAG调用方式保存失败';
        }
        throw error;
      } finally {
        if (this.sessionId === sessionId) this.ragSaving = false;
      }
    },

    /**
     * 构建聊天请求；参数是用户、消息和可选 Agent；返回后端请求体。
     */
    buildChatPayload(
      userId: string,
      message: string,
      agentId?: string,
      requestedRunId?: string,
      attachmentIds: string[] = [],
    ): ChatRequest {
      if (this.activeSourceType === 'workflow') {
        const workflow = this.activeWorkflow;
        const session = this.sessions.find((item) => item.sessionId === this.sessionId);
        return {
          workflowId: this.activeWorkflowId,
          workflowVersion: workflow?.publishedVersion || session?.workflowVersion,
          modelCode: this.activeModelCode,
          userId,
          sessionId: this.sessionId,
          requestedRunId: requestedRunId || undefined,
          attachmentIds: attachmentIds.length > 0 ? attachmentIds : undefined,
          message,
        };
      }
      return {
        agentId: agentId || this.activeAgentId,
        userId,
        sessionId: this.sessionId,
        requestedRunId: requestedRunId || undefined,
        attachmentIds: attachmentIds.length > 0 ? attachmentIds : undefined,
        message,
      };
    },

    /**
     * 判断是否已选择运行目标；无参数；返回是否可发送。
     */
    hasActiveTarget() {
      return this.activeSourceType === 'workflow' ? Boolean(this.activeWorkflowId) : Boolean(this.activeAgentId);
    },
  },
});

/**
 * 生成前端临时 ID；无参数；返回可用于列表渲染的字符串。
 */
function createId() {
  return crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`;
}

/** 会话切换时主动结束历史 SSE，防止旧页面后台占用连接。 */
function abortHistoryWorkflowStreams() {
  historyWorkflowControllers.forEach((controller) => controller.abort());
  historyWorkflowControllers.clear();
}

/** 将服务端有效消息转换为聊天视图模型。 */
function toChatMessage(message: SessionMessagePage['items'][number]): ChatMessage {
  return {
    id: message.messageId,
    runId: message.runId,
    role: message.role,
    content: message.content,
    createdAt: message.createTime,
    status: 'done',
    traceId: message.traceId,
  };
}

/**
 * 计算流式差量；参数是上一份快照和新内容；返回应展示的增量和最新快照。
 */
function resolveStreamChunk(previousSnapshot: string, incoming: string) {
  if (!incoming) {
    return { delta: '', snapshot: previousSnapshot };
  }
  if (incoming === previousSnapshot) {
    return { delta: '', snapshot: previousSnapshot };
  }
  if (previousSnapshot && incoming.startsWith(previousSnapshot)) {
    return {
      delta: incoming.slice(previousSnapshot.length),
      snapshot: incoming,
    };
  }
  return {
    delta: incoming,
    snapshot: previousSnapshot + incoming,
  };
}

/**
 * 创建打字机队列；参数是追加回调；返回推送和等待队列清空的方法。
 */
function createTypewriter(apply: (content: string) => void) {
  let queue = '';
  let timer: ReturnType<typeof setTimeout> | null = null;
  let paused = false;
  let drainResolvers: Array<() => void> = [];

  const resolveDrain = () => {
    drainResolvers.forEach((resolve) => resolve());
    drainResolvers = [];
  };

  const tick = () => {
    if (paused) {
      timer = null;
      return;
    }
    if (!queue) {
      timer = null;
      resolveDrain();
      return;
    }
    const step = Math.min(queue.length, Math.max(1, Math.ceil(queue.length / 28)));
    apply(queue.slice(0, step));
    queue = queue.slice(step);
    timer = setTimeout(tick, 18);
  };

  return {
    /**
     * 推入待展示文本；参数是文本；无返回值。
     */
    push(content: string) {
      queue += content;
      if (!timer && !paused) {
        tick();
      }
    },
    /**
     * 等待文本展示完成；无参数；返回完成 Promise。
     */
    drain() {
      if (!queue && !timer) {
        return Promise.resolve();
      }
      return new Promise<void>((resolve) => {
        drainResolvers.push(resolve);
      });
    },
    /**
     * 暂停展示队列；无参数；保留待展示文本供失败恢复。
     */
    pause() {
      paused = true;
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
    },
    /**
     * 恢复展示队列；无参数；继续输出暂存文本。
     */
    resume() {
      paused = false;
      if (queue && !timer) {
        tick();
      } else if (!queue) {
        resolveDrain();
      }
    },
    /**
     * 取消待展示文本；无参数；停止定时器并释放等待者。
     */
    cancel() {
      queue = '';
      paused = false;
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      resolveDrain();
    },
  };
}

/**
 * 等待指定时间；参数是毫秒数；返回定时完成 Promise。
 */
function wait(milliseconds: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, milliseconds));
}

/**
 * 判断异常是否由主动中断产生；参数是异常值；返回是否为 AbortError。
 */
function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
    || error instanceof Error && error.name === 'CanceledError';
}

/**
 * 创建浏览器侧可预知运行ID；无参数；返回符合服务端约束的运行ID。
 */
function createRunId() {
  return `run_${crypto.randomUUID()}`;
}

/**
 * 读取第一条用户消息；参数是消息列表；返回适合作为标题的文本。
 */
function firstUserMessage(messages: ChatMessage[]) {
  return messages.find((message) => message.role === 'user')?.content || '';
}

/**
 * 压缩会话标题；参数是原始标题；返回短标题。
 */
function compactTitle(title: string) {
  const normalized = title.replace(/\s+/g, ' ').trim();
  return normalized.length > 24 ? `${normalized.slice(0, 24)}...` : normalized || '新会话';
}
