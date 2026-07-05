import { defineStore } from 'pinia';

import {
  createChatSession,
  queryAgentConfigs,
  sendChatMessage,
  sendChatMessageStream,
} from '@/api/agent';
import { queryWorkflowNodeOptions, queryWorkflows } from '@/api/workflow';
import { useAuthStore } from '@/stores/auth';
import type { AiAgentConfig, ChatMessage, ChatRequest, LocalChatSession, WorkflowOption, WorkflowSummary } from '@/types/api';

const SESSION_STORAGE_PREFIX = 'ai_agent_scaffold_chat_sessions';

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
  streaming: boolean;
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
    streaming: true,
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
    selectAgent(agentId: string) {
      this.activeSourceType = 'agent';
      this.activeAgentId = agentId;
      this.sessionId = '';
      this.messages = [];
      this.errorMessage = '';
    },

    /**
     * 切换工作流；参数是工作流 ID；会清空当前会话草稿。
     */
    selectWorkflow(workflowId: string) {
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
    switchSession(sessionId: string) {
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
    async send(message: string) {
      if (!message.trim() || !this.hasActiveTarget()) {
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
      this.sending = true;
      this.errorMessage = '';
      this.saveCurrentSession(userMessage.content);

      try {
        if (this.streaming) {
          const typewriter = createTypewriter((content) => {
            this.appendMessageContent(assistantMessageId, content);
          });
          let streamSnapshot = '';
          await sendChatMessageStream(
            this.buildChatPayload(auth.userId, userMessage.content),
            {
              onSession: (sessionId) => {
                this.sessionId = sessionId;
                this.saveCurrentSession(userMessage.content);
              },
              onChunk: (content) => {
                const result = resolveStreamChunk(streamSnapshot, content);
                streamSnapshot = result.snapshot;
                if (result.delta) {
                  typewriter.push(result.delta);
                }
              },
              onError: (error) => {
                this.errorMessage = error;
              },
            },
          );
          await typewriter.drain();
          this.updateMessageStatus(assistantMessageId, 'done');
          this.saveCurrentSession(userMessage.content);
          return;
        }

        const response = await sendChatMessage(this.buildChatPayload(auth.userId, userMessage.content));
        this.sessionId = response.sessionId;
        this.replaceMessageContent(assistantMessageId, response.content);
        this.updateMessageStatus(assistantMessageId, 'done');
        this.saveCurrentSession(userMessage.content);
      } catch (error) {
        this.updateMessageStatus(assistantMessageId, 'error');
        this.replaceMessageContent(assistantMessageId, '这次对话请求失败了，请检查后端服务、令牌状态或模型配置。');
        this.errorMessage = error instanceof Error ? error.message : '发送失败';
        this.saveCurrentSession(userMessage.content);
      } finally {
        this.sending = false;
      }
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
    buildChatPayload(userId: string, message: string, agentId?: string): ChatRequest {
      if (this.activeSourceType === 'workflow') {
        const workflow = this.activeWorkflow;
        return {
          workflowId: this.activeWorkflowId,
          workflowVersion: workflow?.publishedVersion || undefined,
          modelCode: this.activeModelCode,
          userId,
          sessionId: this.sessionId,
          message,
        };
      }
      return {
        agentId: agentId || this.activeAgentId,
        userId,
        sessionId: this.sessionId,
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
  let drainResolvers: Array<() => void> = [];

  const resolveDrain = () => {
    drainResolvers.forEach((resolve) => resolve());
    drainResolvers = [];
  };

  const tick = () => {
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
      if (!timer) {
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
  };
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
