<template>
  <div class="page chat-page">
    <SectionHeader
      title="智能体会话"
      description="当前聊天链路直接使用后端 JWT 身份、持久化会话和流式接口。模型密钥仍由系统内部配置提供，用户暂不需要填写。"
    >
      <template #actions>
        <div class="button-row">
          <button class="button" type="button" @click="chatStore.loadAgents">刷新运行目标</button>
          <button class="button button--primary" type="button" :disabled="!canCreateSession" @click="createSession">
            新建会话
          </button>
        </div>
      </template>
    </SectionHeader>

    <section class="chat-layout">
      <div class="chat-main card">
        <div class="chat-toolbar">
          <div class="field">
            <label for="source">运行类型</label>
            <select id="source" v-model="chatStore.activeSourceType" class="select" @change="onSourceChanged">
              <option value="agent">系统 Agent</option>
              <option value="workflow">数据库工作流</option>
            </select>
          </div>
          <div v-if="chatStore.activeSourceType === 'agent'" class="field">
            <label for="agent">当前智能体</label>
            <select id="agent" v-model="chatStore.activeAgentId" class="select" @change="onAgentChanged">
              <option v-for="agent in chatStore.agents" :key="agent.agentId" :value="agent.agentId">
                {{ agent.agentName }} · {{ agent.agentId }}
              </option>
            </select>
          </div>
          <div v-else class="field">
            <label for="workflow">当前工作流</label>
            <select id="workflow" v-model="chatStore.activeWorkflowId" class="select" @change="onWorkflowChanged">
              <option v-for="workflow in chatStore.workflows" :key="workflow.workflowId" :value="workflow.workflowId">
                {{ workflow.workflowName }} · v{{ workflow.publishedVersion }}
              </option>
            </select>
          </div>
          <div v-if="chatStore.activeSourceType === 'workflow'" class="field">
            <label for="model">本次模型</label>
            <select id="model" v-model="chatStore.activeModelCode" class="select">
              <option v-for="model in chatStore.models" :key="model.value" :value="model.value">
                {{ model.label }}
              </option>
            </select>
          </div>
          <div class="session-chip">
            <span>sessionId</span>
            <strong>{{ chatStore.sessionId || '发送首条消息时自动创建' }}</strong>
          </div>
        </div>

        <div class="message-list">
          <div v-if="chatStore.messages.length === 0" class="empty-chat">
            <span class="brand-mark">AI</span>
            <h2>开始一轮企业智能体会话</h2>
            <p>你可以先问一个业务问题，前端会用 POST SSE 调用 `/api/v1/chat_stream`，并把后端返回片段实时追加到消息区。</p>
          </div>

          <article
            v-for="message in chatStore.messages"
            :key="message.id"
            :class="['message', `message--${message.role}`]"
          >
            <div class="message__meta">
            <span>{{ roleLabel(message.role) }}</span>
            <span>{{ formatTime(message.createdAt) }}</span>
              <span v-if="chatStore.activeSourceType === 'workflow'" class="badge">{{ chatStore.activeModelCode }}</span>
              <span v-if="message.status === 'streaming'" class="badge">生成中</span>
              <span v-if="message.status === 'error'" class="badge badge--red">失败</span>
            </div>
            <p>{{ message.content || '...' }}</p>
          </article>
        </div>

        <form class="composer" @submit.prevent="send">
          <textarea
            v-model="draft"
            class="textarea composer__input"
            placeholder="输入消息，Enter 发送，Shift + Enter 换行"
            @keydown.enter.exact.prevent="send"
          />
          <div class="composer__bar">
            <div class="button-row">
              <button class="button" type="button" disabled>上传附件（占位）</button>
              <label class="stream-toggle">
                <input v-model="chatStore.streaming" type="checkbox" />
                <span>流式响应</span>
              </label>
            </div>
            <button class="button button--primary" type="submit" :disabled="chatStore.sending || !draft.trim() || !canCreateSession">
              {{ chatStore.sending ? '发送中...' : '发送' }}
            </button>
          </div>
          <span v-if="chatStore.errorMessage" class="error-text">{{ chatStore.errorMessage }}</span>
        </form>
      </div>

      <aside class="chat-side">
        <div class="card">
          <div class="card__body">
            <SectionHeader title="会话切换" description="当前先在浏览器本地记录最近 30 个会话，后续接后端 chat_session 查询。" :level="2" />
            <div class="session-list">
              <button
                v-for="session in visibleSessions"
                :key="session.sessionId"
                :class="['session-item', { 'session-item--active': session.sessionId === chatStore.sessionId }]"
                type="button"
                @click="chatStore.switchSession(session.sessionId)"
              >
                <strong>{{ session.title }}</strong>
                <span>{{ session.agentName }} · {{ formatTime(session.updatedAt) }}</span>
              </button>
              <div v-if="visibleSessions.length === 0" class="session-empty">
                还没有会话。点击“新建会话”或直接发送消息后会出现在这里。
              </div>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card__body">
            <SectionHeader title="上下文窗口" description="第一版先展示规划位，后续接 context manager 实际 token 统计。" :level="2" />
            <div class="context-meter">
              <div>
                <strong>18.4K / 128K</strong>
                <span>示例占用，等待后端上下文管理 API</span>
              </div>
              <div class="progress"><span style="width: 14.4%" /></div>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card__body">
            <SectionHeader title="Token 明细" description="Grafana 已可看 token_usage，这里后续接数据库或 Loki 查询。" :level="2" />
            <div class="token-mini">
              <span>promptTokens</span>
              <strong>--</strong>
              <span>candidateTokens</span>
              <strong>--</strong>
              <span>totalTokens</span>
              <strong>--</strong>
            </div>
          </div>
        </div>

        <FeaturePlaceholder
          title="会话附件"
          description="文件上传、OSS 地址、解析状态和消息引用关系会集中在这里。"
          status="待接 API"
          :items="['上传 PDF / Word / 图片', '绑定当前 sessionId', '进入 RAG 或临时上下文']"
        />
      </aside>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import FeaturePlaceholder from '@/components/common/FeaturePlaceholder.vue';
import SectionHeader from '@/components/common/SectionHeader.vue';
import { useChatStore } from '@/stores/chat';
import type { ChatMessage } from '@/types/api';

const chatStore = useChatStore();
const draft = ref('');
const canCreateSession = computed(() => chatStore.hasActiveTarget());
const visibleSessions = computed(() => {
  return chatStore.sessions.filter((session) => {
    if (chatStore.activeSourceType === 'workflow') {
      return session.sourceType === 'workflow' && session.workflowId === chatStore.activeWorkflowId;
    }
    return (session.sourceType || 'agent') === 'agent' && session.agentId === chatStore.activeAgentId;
  });
});

onMounted(async () => {
  if (chatStore.agents.length === 0) {
    await chatStore.loadAgents();
  }
});

/**
 * 创建新会话；无参数；成功后把 sessionId 写入页面状态。
 */
async function createSession() {
  await chatStore.createSession();
}

/**
 * 智能体变更处理；无参数；清空当前会话，避免串 Agent。
 */
function onAgentChanged() {
  chatStore.selectAgent(chatStore.activeAgentId);
}

/**
 * 运行类型变更处理；无参数；切到对应默认目标。
 */
function onSourceChanged() {
  if (chatStore.activeSourceType === 'workflow') {
    if (!chatStore.activeWorkflowId && chatStore.workflows.length > 0) {
      chatStore.selectWorkflow(chatStore.workflows[0].workflowId);
    } else {
      chatStore.selectWorkflow(chatStore.activeWorkflowId);
    }
    return;
  }
  chatStore.selectAgent(chatStore.activeAgentId);
}

/**
 * 工作流变更处理；无参数；清空当前会话，避免串工作流。
 */
function onWorkflowChanged() {
  chatStore.selectWorkflow(chatStore.activeWorkflowId);
}

/**
 * 发送输入消息；无参数；成功后清空输入框。
 */
async function send() {
  const message = draft.value.trim();
  if (!message || chatStore.sending) {
    return;
  }
  draft.value = '';
  await chatStore.send(message);
}

/**
 * 格式化消息时间；参数是 ISO 时间；返回本地短时间。
 */
function formatTime(value: string) {
  return new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

/**
 * 转换消息角色；参数是消息角色；返回中文展示名。
 */
function roleLabel(role: ChatMessage['role']) {
  const labels = {
    user: '你',
    assistant: '智能体',
    system: '系统',
  };
  return labels[role];
}
</script>

<style scoped>
.chat-page {
  display: grid;
  gap: 22px;
}

.chat-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 20px;
  align-items: start;
}

.chat-main {
  display: grid;
  min-height: calc(100vh - 170px);
  grid-template-rows: auto minmax(420px, 1fr) auto;
}

.chat-toolbar {
  display: grid;
  grid-template-columns: 150px minmax(220px, 1fr) minmax(180px, 240px) minmax(0, 1fr);
  gap: 16px;
  padding: 20px;
  border-bottom: 1px solid var(--line);
}

.session-chip {
  display: grid;
  align-content: center;
  gap: 6px;
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface-muted);
}

.session-chip span {
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.session-chip strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-list {
  display: grid;
  align-content: start;
  gap: 14px;
  overflow-y: auto;
  padding: 20px;
}

.empty-chat {
  display: grid;
  place-items: center;
  align-content: center;
  min-height: 420px;
  color: var(--muted);
  text-align: center;
}

.empty-chat h2 {
  margin: 18px 0 8px;
  color: var(--ink);
}

.empty-chat p {
  max-width: 520px;
  margin: 0;
  line-height: 1.7;
}

.message {
  max-width: min(760px, 92%);
  padding: 16px 18px;
  border: 1px solid var(--line);
  border-radius: 20px;
  background: var(--surface);
}

.message--user {
  justify-self: end;
  color: #fffaf0;
  border-color: var(--accent);
  background: var(--accent);
}

.message--system {
  max-width: 100%;
  color: var(--warning);
  border-color: var(--warning-soft);
  background: var(--warning-soft);
}

.message__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  color: inherit;
  opacity: 0.74;
  font-size: 12px;
  font-weight: 800;
}

.message p {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.8;
}

.composer {
  display: grid;
  gap: 12px;
  padding: 18px;
  border-top: 1px solid var(--line);
  background: rgba(255, 253, 248, 0.72);
}

.composer__input {
  min-height: 110px;
}

.composer__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.stream-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 13px;
  font-weight: 800;
}

.chat-side {
  display: grid;
  gap: 16px;
}

.session-list {
  display: grid;
  gap: 10px;
  margin-top: 18px;
}

.session-item {
  display: grid;
  gap: 6px;
  width: 100%;
  padding: 12px 14px;
  text-align: left;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface-muted);
  cursor: pointer;
  transition: border-color 160ms ease, background 160ms ease, transform 160ms ease;
}

.session-item:hover {
  transform: translateY(-1px);
  border-color: var(--line-strong);
  background: #fff;
}

.session-item--active {
  border-color: rgba(31, 83, 98, 0.38);
  background: var(--accent-soft);
}

.session-item strong {
  overflow: hidden;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-item span,
.session-empty {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.context-meter,
.token-mini {
  display: grid;
  gap: 12px;
  margin-top: 18px;
}

.context-meter strong {
  display: block;
  font-size: 26px;
  letter-spacing: -0.04em;
}

.context-meter span,
.token-mini span {
  color: var(--muted);
  font-size: 13px;
}

.token-mini {
  grid-template-columns: 1fr auto;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface-muted);
}

@media (max-width: 1180px) {
  .chat-layout,
  .chat-toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
