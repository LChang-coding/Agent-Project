import { defineStore } from 'pinia';

import {
  cancelChatRun,
  createChatSession,
  queryAgentConfigs,
  sendChatMessage,
  sendChatMessageStream,
  steerChatRun,
} from '@/api/agent';
import { queryWorkflowNodeOptions, queryWorkflows } from '@/api/workflow';
import { useAuthStore } from '@/stores/auth';
import type {
  AiAgentConfig,
  ChatMessage,
  ChatRequest,
  LocalChatSession,
  RunStreamEvent,
  WorkflowOption,
  WorkflowSummary,
} from '@/types/api';

const SESSION_STORAGE_PREFIX = 'ai_agent_scaffold_chat_sessions';

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

interface ChatState {
  agents: AiAgentConfig[];
  workflows: WorkflowSummary[];
  models: WorkflowOption[];
  activeSourceType: 'agent' | 'workflow';
  activeAgentId: string;
  activeWorkflowId: string;
  activeModelCode: string;
  sessionId: string;
  messages: ChatMessage[];
  sessions: LocalChatSession[];
  loadingAgents: boolean;
  loadingWorkflows: boolean;
  sending: boolean;
  cancelling: boolean;
  steering: boolean;
  streaming: boolean;
  currentRunId: string;
  contextRevision: number;
  errorMessage: string;
}

export const useChatStore = defineStore('chat', {
  state: (): ChatState => ({
    agents: [],
    workflows: [],
    models: [],
    activeSourceType: 'agent',
    activeAgentId: '',
    activeWorkflowId: '',
    activeModelCode: 'deepseek-v4-flash',
    sessionId: '',
    messages: [],
    sessions: [],
    loadingAgents: false,
    loadingWorkflows: false,
    sending: false,
    cancelling: false,
    steering: false,
    streaming: true,
    currentRunId: '',
    contextRevision: 0,
    errorMessage: '',
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
        this.restoreSessions();
        if (!this.activeAgentId && this.agents.length > 0) {
          this.activeAgentId = this.agents[0].agentId;
        }
        if (!this.activeWorkflowId && this.workflows.length > 0) {
          this.activeWorkflowId = this.workflows[0].workflowId;
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
      this.errorMessage = '';
    },

    /**
     * 切换工作流；参数是工作流 ID；会清空当前会话草稿。
     */
    async selectWorkflow(workflowId: string) {
      await this.cancelActiveRun('切换工作流');
      this.activeSourceType = 'workflow';
      this.activeWorkflowId = workflowId;
      const workflow = this.workflows.find((item) => item.workflowId === workflowId);
      if (workflow?.defaultModelCode) {
        this.activeModelCode = workflow.defaultModelCode;
      }
      this.sessionId = '';
      this.messages = [];
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
      this.messages = [
        {
          id: createId(),
          role: 'system',
          content: '新会话已创建，后端会按当前 JWT 身份隔离租户和用户。',
          createdAt: new Date().toISOString(),
          status: 'done',
        },
      ];
      this.saveCurrentSession('新会话');
      return result.sessionId;
    },

    /**
     * 切换本地会话；参数是会话 ID；恢复该会话的 Agent 和消息。
     */
    async switchSession(sessionId: string) {
      if (sessionId === this.sessionId) {
        return;
      }
      await this.cancelActiveRun('切换会话');
      const session = this.sessions.find((item) => item.sessionId === sessionId);
      if (!session) {
        return;
      }
      this.activeSourceType = session.sourceType || 'agent';
      if (this.activeSourceType === 'workflow') {
        this.activeWorkflowId = session.workflowId || session.agentId;
        this.activeModelCode = session.modelCode || this.activeModelCode;
      } else {
        this.activeAgentId = session.agentId;
      }
      this.sessionId = session.sessionId;
      this.messages = session.messages.map((message) => ({ ...message }));
      this.errorMessage = '';
    },

    /**
     * 发送聊天消息；参数是用户输入；根据开关返回流式或完整回复。
     */
    async send(message: string, requestedRunId = '') {
      if (!message.trim() || !this.hasActiveTarget() || this.sending) {
        return;
      }

      const auth = useAuthStore();
      const userMessage: ChatMessage = {
        id: createId(),
        role: 'user',
        content: message.trim(),
        createdAt: new Date().toISOString(),
        status: 'done',
      };
      const assistantMessage: ChatMessage = {
        id: createId(),
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
      const effectiveRunId = requestedRunId || createRunId();
      const request: ActiveChatRequest = {
        generation: ++requestGeneration,
        sessionId: this.sessionId,
        runId: effectiveRunId,
        assistantMessageId,
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
        if (this.streaming) {
          const typewriter = createTypewriter((content) => {
            if (this.isRequestWritable(request)) {
              this.appendMessageContent(assistantMessageId, content);
            }
          });
          request.typewriter = typewriter;
          let streamSnapshot = '';
          await sendChatMessageStream(
            this.buildChatPayload(auth.userId, userMessage.content, undefined, effectiveRunId),
            {
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
          this.buildChatPayload(auth.userId, userMessage.content, undefined, effectiveRunId),
          controller.signal,
        );
        if (!this.isRequestWritable(request)) {
          return;
        }
        request.sessionId = response.sessionId;
        request.runId = response.runId;
        this.currentRunId = response.runId;
        this.contextRevision = response.contextRevision;
        this.sessionId = response.sessionId;
        this.replaceMessageContent(assistantMessageId, response.content);
        this.updateMessageStatus(assistantMessageId, 'done');
        this.saveCurrentSession(userMessage.content);
      } catch (error) {
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
        try {
          if (!request.runId && this.streaming) {
            await Promise.race([request.runReady, wait(1_500)]);
          }
          if (request.runId) {
            const result = await cancelChatRun(request.runId, reason);
            this.contextRevision = result.contextRevision;
          }
        } catch (error) {
          this.errorMessage = error instanceof Error ? `取消请求失败：${error.message}` : '取消请求失败';
        } finally {
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
          void this.send(normalizedInstruction, successor.runId);
          return true;
        } catch (error) {
          this.errorMessage = error instanceof Error ? `引导失败：${error.message}` : '引导失败';
          if (this.isRequestCurrent(request)) {
            request.cancelRequested = false;
            const previousAssistant = this.messages.find((item) => item.id === request.assistantMessageId);
            if (request.streamSettled) {
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
      request.resolveRunReady();
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
     * 恢复本地会话列表；无参数；从浏览器缓存加载当前用户的会话。
     */
    restoreSessions() {
      const raw = localStorage.getItem(sessionStorageKey());
      if (!raw) {
        this.sessions = [];
        return;
      }
      try {
        this.sessions = JSON.parse(raw) as LocalChatSession[];
      } catch {
        this.sessions = [];
      }
    },

    /**
     * 保存当前会话；参数是默认标题；把当前会话写入本地会话列表。
     */
    saveCurrentSession(defaultTitle: string) {
      if (!this.sessionId || !this.hasActiveTarget()) {
        return;
      }
      const activeAgent = this.activeAgent;
      const activeWorkflow = this.activeWorkflow;
      const runtimeName = this.activeSourceType === 'workflow'
        ? activeWorkflow?.workflowName || this.activeWorkflowId
        : activeAgent?.agentName || this.activeAgentId;
      const title = firstUserMessage(this.messages) || defaultTitle || runtimeName || '新会话';
      const session: LocalChatSession = {
        sessionId: this.sessionId,
        agentId: this.activeSourceType === 'workflow' ? this.activeWorkflowId : this.activeAgentId,
        agentName: runtimeName,
        sourceType: this.activeSourceType,
        workflowId: this.activeSourceType === 'workflow' ? this.activeWorkflowId : undefined,
        workflowName: this.activeSourceType === 'workflow' ? runtimeName : undefined,
        modelCode: this.activeSourceType === 'workflow' ? this.activeModelCode : undefined,
        title: compactTitle(title),
        updatedAt: new Date().toISOString(),
        messages: this.messages.map((message) => ({ ...message })),
      };
      const nextSessions = [session, ...this.sessions.filter((item) => item.sessionId !== session.sessionId)].slice(0, 30);
      this.sessions = nextSessions;
      localStorage.setItem(sessionStorageKey(), JSON.stringify(nextSessions));
    },

    /**
     * 构建聊天请求；参数是用户、消息和可选 Agent；返回后端请求体。
     */
    buildChatPayload(userId: string, message: string, agentId?: string, requestedRunId?: string): ChatRequest {
      if (this.activeSourceType === 'workflow') {
        const workflow = this.activeWorkflow;
        return {
          workflowId: this.activeWorkflowId,
          workflowVersion: workflow?.publishedVersion || undefined,
          modelCode: this.activeModelCode,
          userId,
          sessionId: this.sessionId,
          requestedRunId: requestedRunId || undefined,
          message,
        };
      }
      return {
        agentId: agentId || this.activeAgentId,
        userId,
        sessionId: this.sessionId,
        requestedRunId: requestedRunId || undefined,
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
 * 读取本地会话缓存键；无参数；返回当前用户隔离的缓存键。
 */
function sessionStorageKey() {
  const auth = useAuthStore();
  return `${SESSION_STORAGE_PREFIX}:${auth.tenantId || 'tenant'}:${auth.userId || 'user'}`;
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
