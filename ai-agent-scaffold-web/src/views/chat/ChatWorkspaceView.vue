<template>
  <div class="page chat-page">
    <section class="chat-workbench">
      <aside class="session-rail" aria-label="会话列表">
        <div class="rail-head">
          <span class="rail-kicker">Sessions</span>
          <button class="rail-new" type="button" :disabled="!canCreateSession" @click="createSession">新建</button>
        </div>

        <div class="session-list">
          <div
            v-for="session in visibleSessions"
            :key="session.sessionId"
            :class="['session-item', { 'session-item--active': session.sessionId === chatStore.sessionId }]"
          >
            <button class="session-open" type="button" @click="switchSession(session.sessionId)">
              <strong>{{ session.title }}</strong>
              <span>{{ session.agentName }} · {{ formatTime(session.updatedAt) }}</span>
            </button>
            <button class="session-delete" type="button" :disabled="chatStore.deletingSessionId === session.sessionId"
                    :aria-label="`删除会话 ${session.title}`" @click.stop="deleteSession(session.sessionId, session.title)">
              {{ chatStore.deletingSessionId === session.sessionId ? '…' : '×' }}
            </button>
          </div>
          <div v-if="visibleSessions.length === 0" class="session-empty">
            还没有会话，发送第一条消息后会自动归档在这里。
          </div>
        </div>
      </aside>

      <main class="chat-stage">
        <header class="chat-commandbar">
          <div class="chat-title">
            <span class="status-dot" />
            <div>
              <h1>智能体会话</h1>
              <p>{{ currentTargetText }}</p>
            </div>
          </div>

          <div class="runtime-controls">
            <label class="compact-field">
              <span>运行</span>
              <select v-model="chatStore.activeSourceType" class="select select--compact" :disabled="chatStore.sending" @change="onSourceChanged">
                <option value="agent">系统 Agent</option>
                <option value="workflow">数据库工作流</option>
              </select>
            </label>
            <label v-if="chatStore.activeSourceType === 'agent'" class="compact-field compact-field--wide">
              <span>目标</span>
              <select v-model="chatStore.activeAgentId" class="select select--compact" :disabled="chatStore.sending" @change="onAgentChanged">
                <option v-for="agent in chatStore.agents" :key="agent.agentId" :value="agent.agentId">
                  {{ agent.agentName }}
                </option>
              </select>
            </label>
            <label v-else class="compact-field compact-field--wide">
              <span>工作流</span>
              <select v-model="chatStore.activeWorkflowId" class="select select--compact" :disabled="chatStore.sending" @change="onWorkflowChanged">
                <option v-for="workflow in chatStore.workflows" :key="workflow.workflowId" :value="workflow.workflowId">
                  {{ workflow.workflowName }} · v{{ workflow.publishedVersion }}
                </option>
              </select>
            </label>
            <label v-if="chatStore.activeSourceType === 'workflow'" class="compact-field">
              <span>模型</span>
              <select v-model="chatStore.activeModelCode" class="select select--compact" :disabled="chatStore.sending">
                <option v-for="model in chatStore.models" :key="model.value" :value="model.value">
                  {{ model.label }}
                </option>
              </select>
            </label>
            <button class="icon-button" type="button" title="刷新运行目标" :disabled="chatStore.sending" @click="reloadTargets">刷新</button>
            <button class="icon-button" type="button" title="分享当前会话" :disabled="!chatStore.sessionId || chatStore.sending || sharing" @click="shareSession">
              {{ sharing ? '生成中' : '分享' }}
            </button>
          </div>
        </header>

        <div v-if="shareLink" class="share-strip">
          <span>安全分享已生成：{{ shareLink }}</span>
          <button type="button" class="button button--soft" @click="copyShareLink">复制链接</button>
          <button type="button" class="icon-button" aria-label="关闭分享提示" @click="shareLink = ''">关闭</button>
        </div>

        <div ref="messageListRef" class="message-list">
          <div v-if="chatStore.messages.length === 0" class="empty-chat">
            <span class="empty-orb">AI</span>
            <h2>把复杂任务丢进来</h2>
            <p>保持聊天界面干净，工具、上下文、Token 和附件都收进下方的 + 面板。</p>
          </div>

          <TransitionGroup v-else name="message-flow" tag="div" class="message-stack">
            <article
              v-for="message in chatStore.messages"
              :key="message.id"
              :class="['message', `message--${message.role}`, { 'message--streaming': message.status === 'streaming' }]"
            >
              <div class="message__meta">
                <span>{{ roleLabel(message.role) }}</span>
                <span>{{ formatTime(message.createdAt) }}</span>
                <span v-if="message.status === 'streaming'" class="mini-badge">生成中</span>
                <span v-if="message.status === 'error'" class="mini-badge mini-badge--red">失败</span>
                <span v-if="message.status === 'canceled'" class="mini-badge mini-badge--red">已取消</span>
                <span v-if="message.status === 'superseded'" class="mini-badge">已被引导替代</span>
              </div>
              <p>{{ message.content || '...' }}</p>
            </article>
          </TransitionGroup>
        </div>

        <form class="composer" @submit.prevent="send">
          <Transition name="insight-drawer">
            <section v-if="insightPanelOpen" class="insight-panel">
              <nav class="insight-tabs" aria-label="会话洞察">
                <button
                  v-for="tab in insightTabs"
                  :key="tab.value"
                  :class="['insight-tab', { 'insight-tab--active': activeInsightTab === tab.value }]"
                  type="button"
                  @click="openInsightTab(tab.value)"
                >
                  {{ tab.label }}
                  <span v-if="tab.count !== undefined">{{ tab.count }}</span>
                </button>
              </nav>

              <div class="insight-body">
                <div v-if="activeInsightTab === 'context'" class="context-card">
                  <div>
                    <strong>{{ contextUsageText }}</strong>
                    <span>{{ contextHint }}</span>
                  </div>
                  <div class="progress"><span :style="contextProgressStyle" /></div>
                </div>

                <div v-else-if="activeInsightTab === 'tokens'" class="token-grid">
                  <div>
                    <span>promptTokens</span>
                    <strong>{{ formatOptionalTokens(insightStore.usage?.latest?.promptTokens) }}</strong>
                  </div>
                  <div>
                    <span>candidateTokens</span>
                    <strong>{{ formatOptionalTokens(insightStore.usage?.latest?.candidateTokens) }}</strong>
                  </div>
                  <div>
                    <span>totalTokens</span>
                    <strong>{{ formatOptionalTokens(insightStore.usage?.latest?.totalTokens) }}</strong>
                  </div>
                </div>

                <div v-else-if="activeInsightTab === 'tools'" class="insight-list">
                  <div v-for="tool in toolStore.catalog" :key="`${tool.toolType}-${tool.toolId}`" class="insight-item">
                    <strong>{{ tool.toolName }}</strong>
                    <span>{{ tool.toolType }} · {{ tool.version || '未发布' }} · {{ visibilityLabel(tool.visibility) }}</span>
                  </div>
                  <div v-if="toolStore.catalog.length === 0" class="insight-empty">暂无可用工具。</div>
                </div>

                <div v-else-if="activeInsightTab === 'calls'" class="insight-list">
                  <div v-for="call in toolStore.calls" :key="`${call.toolId}-${call.invocationId}-${call.createTime}`" class="insight-call">
                    <div>
                      <strong>{{ call.toolName }}</strong>
                      <span>{{ call.toolType }} · {{ call.costMs || 0 }}ms · {{ call.traceId || 'no-trace' }}</span>
                    </div>
                    <em :class="call.status === 'success' ? 'call-ok' : 'call-fail'">{{ call.status }}</em>
                    <small v-if="call.errorMessage">{{ call.errorMessage }}</small>
                  </div>
                  <div v-if="toolStore.calls.length === 0" class="insight-empty">当前会话还没有工具调用记录。</div>
                </div>

                <div v-else class="attachment-panel" @dragover.prevent @drop.prevent="onAttachmentDrop">
                  <input ref="attachmentInputRef" class="visually-hidden" type="file" multiple
                         accept=".txt,.md,.pdf,.doc,.docx,image/*" @change="onAttachmentInput" />
                  <button class="attachment-drop" type="button"
                          :disabled="chatStore.sending || assetStore.uploading" @click="openAttachmentPicker">
                    {{ assetStore.uploading ? '上传中…' : '选择或拖入 PDF / Word / 图片' }}
                    <span>单个文件最大 20 MiB，单次最多 10 个；运行期间不可变更附件。</span>
                  </button>
                  <div v-if="assetStore.assets.length > 0" class="attachment-assets">
                    <button
                      v-for="asset in assetStore.assets"
                      :key="asset.assetId"
                      :class="['attachment-asset', { 'attachment-asset--selected': assetStore.isSelected(asset.assetId) }]"
                      type="button"
                      :disabled="chatStore.sending || asset.parseStatus !== 'ready'"
                      @click="assetStore.toggleSelected(asset)"
                    >
                      <strong>{{ asset.fileName }}</strong>
                      <span>{{ formatFileSize(asset.sizeBytes) }} · {{ parseStatusLabel(asset.parseStatus) }}</span>
                    </button>
                  </div>
                  <span v-if="assetStore.errorMessage" class="error-text">{{ assetStore.errorMessage }}</span>
                </div>
              </div>
            </section>
          </Transition>

          <div class="composer-surface">
            <div class="composer-meta">
              <button
                :class="['composer-plus', { 'composer-plus--active': insightPanelOpen }]"
                type="button"
                :aria-expanded="insightPanelOpen"
                @click="toggleInsightPanel"
              >
                +
              </button>
              <span>{{ sessionText }}</span>
              <label class="stream-toggle">
                <input v-model="chatStore.streaming" type="checkbox" :disabled="chatStore.sending" />
                <span>流式</span>
              </label>
            </div>

            <div v-if="assetStore.readySelectedAssets.length > 0" class="attachment-chips" aria-label="待发送附件">
              <span v-for="asset in assetStore.readySelectedAssets" :key="asset.assetId" class="attachment-chip">
                {{ asset.fileName }}
                <button type="button" :disabled="chatStore.sending" :aria-label="`移除附件 ${asset.fileName}`"
                        @click="assetStore.toggleSelected(asset)">×</button>
              </span>
            </div>

            <textarea
              v-model="draft"
              class="composer-input"
              :placeholder="chatStore.sending ? '输入新指令引导当前运行' : '输入消息，Enter 发送，Shift + Enter 换行'"
              @compositionstart="onCompositionStart"
              @compositionend="onCompositionEnd"
              @keydown.enter="onComposerEnter"
            />

            <div class="composer-actions">
              <span class="composer-hint">
                {{ chatStore.sending ? '输入新指令后可引导当前运行' : 'Enter 发送 · 输入法候选期间不会误发' }}
              </span>
              <div class="button-row">
                <button
                  v-if="chatStore.sending"
                  class="button button--soft"
                  type="button"
                  :disabled="!draft.trim() || !chatStore.currentRunId || chatStore.steering || chatStore.cancelling"
                  @click="steerRun"
                >
                  {{ chatStore.steering ? '引导中' : '引导' }}
                </button>
                <button
                  :class="['button', chatStore.sending ? 'button--danger' : 'button--primary']"
                  :type="chatStore.sending ? 'button' : 'submit'"
                  :disabled="chatStore.cancelling || chatStore.steering || (!chatStore.sending && (!draft.trim() || !canCreateSession))"
                  @click="chatStore.sending ? cancelRun() : undefined"
                >
                  {{ chatStore.cancelling ? '取消中' : chatStore.sending ? '取消' : '发送' }}
                </button>
              </div>
            </div>
          </div>

          <span v-if="chatStore.errorMessage" class="error-text">{{ chatStore.errorMessage }}</span>
        </form>
      </main>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';

import { createSessionShare } from '@/api/share';
import { useAssetStore } from '@/stores/assets';
import { useChatStore } from '@/stores/chat';
import { useInsightStore } from '@/stores/insight';
import { useToolStore } from '@/stores/tools';
import type { ChatMessage } from '@/types/api';

type InsightTab = 'context' | 'tokens' | 'tools' | 'calls' | 'assets';

const chatStore = useChatStore();
const assetStore = useAssetStore();
const insightStore = useInsightStore();
const toolStore = useToolStore();
const draft = ref('');
const isComposing = ref(false);
const insightPanelOpen = ref(false);
const activeInsightTab = ref<InsightTab>('context');
const messageListRef = ref<HTMLElement | null>(null);
const attachmentInputRef = ref<HTMLInputElement | null>(null);
const sharing = ref(false);
const shareLink = ref('');
const canCreateSession = computed(() => chatStore.hasActiveTarget());
const attachmentScope = computed(() => {
  if (chatStore.sessionId) {
    return `session:${chatStore.sessionId}`;
  }
  const targetId = chatStore.activeSourceType === 'workflow' ? chatStore.activeWorkflowId : chatStore.activeAgentId;
  return `draft:${chatStore.activeSourceType}:${targetId}`;
});
const visibleSessions = computed(() => {
  return chatStore.sessions.filter((session) => {
    if (chatStore.activeSourceType === 'workflow') {
      return session.sourceType === 'workflow' && session.workflowId === chatStore.activeWorkflowId;
    }
    return (session.sourceType || 'agent') === 'agent' && session.agentId === chatStore.activeAgentId;
  });
});
const currentTargetText = computed(() => {
  if (chatStore.activeSourceType === 'workflow') {
    const workflow = chatStore.activeWorkflow;
    return `${workflow?.workflowName || '未选择工作流'} · ${modelLabel(chatStore.activeModelCode)}`;
  }
  return chatStore.activeAgent?.agentName || '未选择智能体';
});
const sessionText = computed(() => {
  return chatStore.sessionId ? `session ${chatStore.sessionId.slice(0, 8)}` : '首条消息自动创建会话';
});
const contextUsageText = computed(() => {
  const context = insightStore.context;
  return context ? `${formatTokens(displayContextTokens.value)} / ${formatTokens(context.modelWindowTokens)}` : '-- / --';
});
const displayContextTokens = computed(() => insightStore.usage?.latest?.promptTokens
  ?? insightStore.context?.effectiveTokens
  ?? 0);
const contextHint = computed(() => {
  if (!chatStore.sessionId) {
    return '请先选择会话。';
  }
  if (insightStore.loadingSession) {
    return '正在读取真实上下文统计...';
  }
  if (insightStore.sessionError) {
    return insightStore.sessionError;
  }
  const context = insightStore.context;
  return context
    ? `${insightStore.usage?.latest ? '最近模型实际 Prompt' : 'Context Manager 当前估算'} · 系统 ${formatTokens(context.systemTokens)} · 历史 ${formatTokens(context.historyTokens)} · 摘要 ${formatTokens(context.summaryTokens)}`
    : '暂无上下文统计。';
});
const contextProgressStyle = computed(() => ({
  width: `${Math.min(100, Math.max(0, insightStore.context?.modelWindowTokens
    ? (displayContextTokens.value / insightStore.context.modelWindowTokens) * 100
    : 0))}%`,
}));
const insightTabs = computed<Array<{ value: InsightTab; label: string; count?: number }>>(() => [
  { value: 'context', label: '上下文' },
  { value: 'tokens', label: 'Token' },
  { value: 'tools', label: '工具', count: insightStore.context?.toolCount ?? 0 },
  { value: 'calls', label: '调用', count: insightStore.context?.callCount ?? 0 },
  { value: 'assets', label: '附件', count: insightStore.context?.attachmentCount ?? 0 },
]);

onMounted(async () => {
  assetStore.setSelectionScope(attachmentScope.value);
  if (chatStore.agents.length === 0) {
    await chatStore.loadAgents();
  }
  await toolStore.loadCatalog();
  if (chatStore.sessionId) {
    await Promise.all([
      toolStore.loadCalls(chatStore.sessionId),
      insightStore.loadSession(chatStore.sessionId),
      refreshSessionAssets(chatStore.sessionId),
    ]);
  } else {
    assetStore.clearList();
  }
  scrollToLatest();
});

watch(
  () => chatStore.sessionId,
  async (sessionId) => {
    await Promise.all([toolStore.loadCalls(sessionId), insightStore.loadSession(sessionId)]);
  },
);

watch(
  attachmentScope,
  async (scope) => {
    assetStore.setSelectionScope(scope);
    if (chatStore.sessionId) {
      await refreshSessionAssets(chatStore.sessionId);
    } else {
      assetStore.clearList();
    }
  },
);

watch(
  () => chatStore.insightRefreshVersion,
  async () => {
    if (chatStore.sessionId) {
      await insightStore.loadSession(chatStore.sessionId, chatStore.lastSettledRunId);
    }
  },
);

watch(
  () => chatStore.messages.map((message) => `${message.id}:${message.content.length}:${message.status}`).join('|'),
  () => scrollToLatest(),
  { flush: 'post' },
);

/**
 * 创建新会话；无参数；成功后刷新工具调用并滚动到底部。
 */
async function createSession() {
  await chatStore.createSession();
  await toolStore.loadCalls(chatStore.sessionId);
  scrollToLatest();
}

/**
 * 切换本地会话；参数是会话 ID；恢复消息并刷新调用记录。
 */
async function switchSession(sessionId: string) {
  await chatStore.switchSession(sessionId);
  await toolStore.loadCalls(sessionId);
  scrollToLatest();
}

/**
 * 删除会话；参数是会话ID和标题；确认后调用服务端软删除。
 */
async function deleteSession(sessionId: string, title: string) {
  if (!window.confirm(`确定删除会话“${title}”吗？历史审计记录会保留，但会话将不再显示。`)) {
    return;
  }
  try {
    await chatStore.deleteSession(sessionId);
    await toolStore.loadCalls(chatStore.sessionId);
  } catch {
    // Store 已保存可展示的服务端错误。
  }
}

/**
 * 刷新运行目标；无参数；重新加载 Agent、工作流和工具目录。
 */
async function reloadTargets() {
  await chatStore.loadAgents();
  await toolStore.loadCatalog();
}

/**
 * 智能体变更处理；无参数；清空当前会话，避免串 Agent。
 */
async function onAgentChanged() {
  await chatStore.selectAgent(chatStore.activeAgentId);
}

/**
 * 运行类型变更处理；无参数；切到对应默认目标。
 */
async function onSourceChanged() {
  if (chatStore.activeSourceType === 'workflow') {
    if (!chatStore.activeWorkflowId && chatStore.workflows.length > 0) {
      await chatStore.selectWorkflow(chatStore.workflows[0].workflowId);
    } else {
      await chatStore.selectWorkflow(chatStore.activeWorkflowId);
    }
    return;
  }
  await chatStore.selectAgent(chatStore.activeAgentId);
}

/**
 * 工作流变更处理；无参数；清空当前会话，避免串工作流。
 */
async function onWorkflowChanged() {
  await chatStore.selectWorkflow(chatStore.activeWorkflowId);
}

/**
 * 切换洞察面板；无参数；打开或关闭底部信息面板。
 */
function toggleInsightPanel() {
  insightPanelOpen.value = !insightPanelOpen.value;
}

/**
 * 打开指定洞察页签；参数是页签编码；按需刷新工具数据。
 */
async function openInsightTab(tab: InsightTab) {
  activeInsightTab.value = tab;
  if (tab === 'tools') {
    await toolStore.loadCatalog();
  }
  if (tab === 'calls') {
    await toolStore.loadCalls(chatStore.sessionId);
  }
  if (tab === 'context' || tab === 'tokens') {
    await insightStore.loadSession(chatStore.sessionId, chatStore.lastSettledRunId);
  }
  if (tab === 'assets' && chatStore.sessionId) {
    await refreshSessionAssets(chatStore.sessionId);
  }
}

/**
 * 打开附件选择器；无参数；运行期间由控件禁用。
 */
function openAttachmentPicker() {
  attachmentInputRef.value?.click();
}

/**
 * 处理文件选择；参数是 input 事件；上传后清空选择器。
 */
async function onAttachmentInput(event: Event) {
  const input = event.target as HTMLInputElement;
  await uploadAttachments(Array.from(input.files || []));
  input.value = '';
}

/**
 * 处理附件拖入；参数是拖放事件；运行期间忽略。
 */
async function onAttachmentDrop(event: DragEvent) {
  if (chatStore.sending || assetStore.uploading) {
    return;
  }
  await uploadAttachments(Array.from(event.dataTransfer?.files || []));
}

/**
 * 上传待发送附件；参数是文件快照；仅选入 ready 资产。
 */
async function uploadAttachments(files: File[]) {
  if (chatStore.sending) {
    return;
  }
  try {
    await assetStore.uploadFiles(files, chatStore.sessionId || undefined);
  } catch (error) {
    assetStore.errorMessage = error instanceof Error ? error.message : '附件上传失败';
  }
}

/**
 * 刷新当前会话附件；参数是会话ID；错误由 Store 展示。
 */
async function refreshSessionAssets(sessionId: string) {
  try {
    await assetStore.loadAssets(sessionId);
  } catch {
    // Store 已保存可展示的服务端错误。
  }
}

/**
 * 输入法开始组合；无参数；标记 Enter 不能触发送出。
 */
function onCompositionStart() {
  isComposing.value = true;
}

/**
 * 输入法结束组合；无参数；恢复 Enter 发送能力。
 */
function onCompositionEnd() {
  isComposing.value = false;
}

/**
 * 处理 Enter 按键；参数是键盘事件；输入法组合时放行，普通 Enter 发送。
 */
function onComposerEnter(event: KeyboardEvent) {
  if (event.shiftKey) {
    return;
  }
  const keyCode = (event as KeyboardEvent & { keyCode?: number }).keyCode;
  if (event.isComposing || isComposing.value || keyCode === 229) {
    return;
  }
  event.preventDefault();
  send();
}

/**
 * 发送输入消息；无参数；成功后清空输入框并刷新工具调用。
 */
async function send() {
  const message = draft.value.trim();
  if (!message || chatStore.sending) {
    return;
  }
  const attachmentIds = [...assetStore.selectedAssetIds];
  draft.value = '';
  await chatStore.send(message, '', attachmentIds);
  assetStore.clearSelected();
  if (chatStore.sessionId) {
    await refreshSessionAssets(chatStore.sessionId);
  }
  await toolStore.loadCatalog();
  await toolStore.loadCalls(chatStore.sessionId);
  scrollToLatest();
}

/**
 * 取消当前运行；无参数；服务端确认后中断本地流并刷新工具记录。
 */
async function cancelRun() {
  await chatStore.cancelActiveRun();
  await toolStore.loadCalls(chatStore.sessionId);
  scrollToLatest();
}

/**
 * 引导当前运行；无参数；成功后清空引导输入并跟踪后继流。
 */
async function steerRun() {
  const instruction = draft.value.trim();
  if (!instruction || chatStore.steering || chatStore.cancelling) {
    return;
  }
  const success = await chatStore.steerActiveRun(instruction);
  if (success) {
    draft.value = '';
  }
  scrollToLatest();
}

/**
 * 创建当前会话安全分享；无参数；展示服务器令牌链接。
 */
async function shareSession() {
  if (!chatStore.sessionId || sharing.value) {
    return;
  }
  sharing.value = true;
  chatStore.errorMessage = '';
  try {
    const result = await createSessionShare(chatStore.sessionId);
    shareLink.value = new URL(result.shareUrl || '', window.location.origin).toString();
    await copyShareLink();
  } catch (error) {
    chatStore.errorMessage = error instanceof Error ? error.message : '创建分享失败';
  } finally {
    sharing.value = false;
  }
}

/**
 * 复制分享链接；无参数；写入系统剪贴板。
 */
async function copyShareLink() {
  if (!shareLink.value) {
    return;
  }
  try {
    await navigator.clipboard.writeText(shareLink.value);
  } catch {
    chatStore.errorMessage = '链接已生成，但浏览器未授权访问剪贴板，请手动复制';
  }
}

/**
 * 滚动到最新消息；无参数；让流式输出始终可见。
 */
function scrollToLatest() {
  nextTick(() => {
    const element = messageListRef.value;
    if (!element) {
      return;
    }
    element.scrollTo({ top: element.scrollHeight, behavior: 'smooth' });
  });
}

/**
 * 格式化消息时间；参数是 ISO 时间；返回本地短时间。
 */
function formatTime(value: string) {
  return new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

/**
 * 格式化文件大小；参数是字节数；返回人类可读文本。
 */
function formatFileSize(sizeBytes: number) {
  if (sizeBytes < 1024) {
    return `${sizeBytes} B`;
  }
  if (sizeBytes < 1024 * 1024) {
    return `${(sizeBytes / 1024).toFixed(1)} KiB`;
  }
  return `${(sizeBytes / 1024 / 1024).toFixed(1)} MiB`;
}

/**
 * 转换解析状态；参数是状态编码；返回中文展示。
 */
function parseStatusLabel(status: string) {
  return ({ ready: '可发送', pending: '解析中', failed: '解析失败' } as Record<string, string>)[status] || status;
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

/**
 * 可见范围展示；参数是范围编码；返回中文展示。
 */
function visibilityLabel(value: string) {
  return value === 'tenant_public' ? '企业公共' : '个人私有';
}

/**
 * 模型展示名；参数是模型编码；返回下拉选项中的名称。
 */
function modelLabel(modelCode: string) {
  return chatStore.models.find((model) => model.value === modelCode)?.label || modelCode;
}

/**
 * 格式化 Token 数；参数是数量；返回紧凑展示文本。
 */
function formatTokens(value: number) {
  return value >= 1000 ? `${(value / 1000).toFixed(value >= 10_000 ? 0 : 1)}K` : String(value);
}

/**
 * 格式化可选 Token 数；参数是可选数量；缺少最新用量时返回占位符。
 */
function formatOptionalTokens(value?: number) {
  return value === undefined || value === null ? '--' : value.toLocaleString('zh-CN');
}
</script>

<style scoped>
.chat-page {
  height: calc(100vh - 52px);
  min-height: 0;
  overflow: hidden;
  padding: 12px;
}

.share-strip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--accent) 8%, var(--panel));
  color: var(--muted);
  font-size: 12px;
}

.share-strip span {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-workbench {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  gap: 10px;
  height: 100%;
  min-height: 0;
}

.session-rail,
.chat-stage {
  overflow: hidden;
  border: 1px solid var(--line);
  background: rgba(252, 252, 250, 0.96);
  box-shadow: none;
}

.session-rail {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
  border-radius: var(--radius-lg);
}

.rail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px;
  border-bottom: 1px solid var(--line);
}

.rail-kicker {
  color: var(--muted);
  font-size: 10px;
  font-weight: 900;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.rail-new,
.icon-button {
  min-height: 30px;
  padding: 0 9px;
  color: var(--accent-deep);
  border: 1px solid transparent;
  border-radius: 7px;
  background: var(--accent-soft);
  cursor: pointer;
  font-size: 13px;
  font-weight: 800;
  transition: transform var(--motion-fast), background var(--motion-fast), border-color var(--motion-fast);
}

.rail-new:hover,
.icon-button:hover {
  border-color: rgba(30, 90, 103, 0.16);
  background: #d7e8e9;
}

.rail-new:disabled {
  cursor: not-allowed;
  opacity: 0.46;
  transform: none;
}

.session-list {
  display: grid;
  align-content: start;
  gap: 3px;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 8px;
}

.session-item {
  display: flex;
  align-items: center;
  width: 100%;
  padding: 10px;
  text-align: left;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  transition: transform var(--motion-fast), border-color var(--motion-fast), background var(--motion-fast);
}

.session-open {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 4px;
  padding: 0;
  text-align: left;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.session-delete {
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  color: var(--muted);
  border: 0;
  border-radius: 7px;
  background: transparent;
  cursor: pointer;
}

.session-delete:hover {
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 10%, transparent);
}

.session-item:hover {
  border-color: transparent;
  background: var(--surface-muted);
}

.session-item--active {
  border-color: rgba(30, 90, 103, 0.16);
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

.session-empty {
  padding: 12px;
}

.chat-stage {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  min-height: 0;
  border-radius: var(--radius-lg);
}

.chat-commandbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
  background: var(--surface-muted);
}

.chat-title {
  display: flex;
  align-items: center;
  min-width: 180px;
  gap: 9px;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--success);
  box-shadow: 0 0 0 4px rgba(45, 107, 79, 0.1);
}

.chat-title h1 {
  margin: 0;
  font-size: 14px;
  letter-spacing: -0.04em;
}

.chat-title p {
  overflow: hidden;
  max-width: 360px;
  margin: 3px 0 0;
  color: var(--muted);
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
}

.runtime-controls {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  min-width: 0;
}

.compact-field {
  display: grid;
  min-width: 112px;
  gap: 3px;
}

.compact-field--wide {
  min-width: min(220px, 22vw);
}

.compact-field span {
  color: var(--muted);
  font-size: 10px;
  font-weight: 900;
  letter-spacing: 0.08em;
}

.select--compact {
  min-height: 32px;
  border-radius: 8px;
  background: var(--surface);
  font-size: 12px;
}

.message-list {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 24px clamp(18px, 5vw, 88px);
  scroll-behavior: smooth;
}

.message-stack {
  display: grid;
  align-content: start;
  gap: 12px;
}

.empty-chat {
  display: grid;
  min-height: 100%;
  place-items: center;
  align-content: center;
  color: var(--muted);
  text-align: center;
}

.empty-orb {
  display: grid;
  width: 48px;
  height: 48px;
  color: var(--accent-deep);
  place-items: center;
  border: 1px solid rgba(31, 83, 98, 0.18);
  border-radius: 14px;
  background: var(--accent-soft);
  box-shadow: none;
  font-weight: 900;
}

.empty-chat h2 {
  margin: 14px 0 6px;
  color: var(--ink);
  font-family: "Fraunces", "Songti SC", serif;
  font-size: clamp(24px, 3vw, 34px);
  letter-spacing: -0.06em;
}

.empty-chat p {
  max-width: 520px;
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
}

.message {
  position: relative;
  max-width: min(760px, 88%);
  padding: 12px 14px;
  border: 1px solid rgba(25, 36, 45, 0.1);
  border-radius: 14px;
  background: var(--surface);
  box-shadow: none;
}

.message--user {
  justify-self: end;
  color: #fffaf0;
  border-color: rgba(31, 83, 98, 0.82);
  background: var(--accent);
}

.message--assistant {
  justify-self: start;
}

.message--system {
  max-width: 100%;
  color: var(--warning);
  border-color: rgba(150, 108, 34, 0.12);
  background: rgba(244, 231, 202, 0.62);
}

.message--streaming::after {
  display: inline-block;
  width: 8px;
  height: 18px;
  margin-left: 4px;
  content: "";
  vertical-align: -3px;
  border-radius: 999px;
  background: currentColor;
  animation: caretPulse 960ms ease-in-out infinite;
}

.message__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 5px;
  color: inherit;
  opacity: 0.68;
  font-size: 11px;
  font-weight: 850;
}

.message p {
  margin: 0;
  white-space: pre-wrap;
  font-size: 14px;
  line-height: 1.7;
}

.mini-badge {
  padding: 2px 6px;
  border-radius: 5px;
  background: rgba(31, 83, 98, 0.1);
}

.mini-badge--red {
  color: var(--danger);
  background: var(--danger-soft);
}

.composer {
  display: grid;
  gap: 8px;
  min-height: 0;
  padding: 0 14px 14px;
}

.composer-surface,
.insight-panel {
  border: 1px solid var(--line);
  background: var(--surface);
  box-shadow: none;
}

.composer-surface {
  overflow: hidden;
  border-radius: 14px;
}

.composer-meta,
.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
}

.composer-meta {
  border-bottom: 1px solid rgba(25, 36, 45, 0.08);
  color: var(--muted);
  font-size: 11px;
  font-weight: 800;
}

.composer-plus {
  display: grid;
  width: 30px;
  height: 30px;
  color: var(--accent-deep);
  place-items: center;
  border: 1px solid rgba(31, 83, 98, 0.16);
  border-radius: 8px;
  background: rgba(31, 83, 98, 0.07);
  cursor: pointer;
  font-size: 19px;
  line-height: 1;
  transition: transform var(--motion-fast), background var(--motion-fast);
}

.composer-plus--active {
  color: #fffaf0;
  background: var(--accent);
  transform: rotate(45deg);
}

.attachment-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 10px 0;
}

.attachment-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 240px;
  padding: 4px 7px;
  color: var(--accent-deep);
  border-radius: 6px;
  background: var(--accent-soft);
  overflow: hidden;
  font-size: 11px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attachment-chip button {
  padding: 0;
  color: inherit;
  border: 0;
  background: transparent;
  cursor: pointer;
  font-weight: 900;
}

.composer-input {
  width: 100%;
  min-height: 84px;
  max-height: 220px;
  padding: 12px 14px;
  color: var(--ink);
  border: 0;
  outline: none;
  resize: vertical;
  background: transparent;
  line-height: 1.7;
}

.composer-actions {
  border-top: 1px solid rgba(25, 36, 45, 0.08);
}

.composer-hint {
  color: var(--muted);
  font-size: 11px;
}

.stream-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
}

.insight-panel {
  overflow: hidden;
  border-radius: 14px 14px 0 0;
  box-shadow: 0 -12px 36px rgba(24, 32, 42, 0.06);
}

.insight-tabs {
  display: flex;
  gap: 2px;
  overflow-x: auto;
  padding: 8px;
  border-bottom: 1px solid var(--line);
}

.insight-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 30px;
  padding: 0 9px;
  color: var(--muted);
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  font-weight: 850;
}

.insight-tab--active {
  color: var(--accent-deep);
  border-color: transparent;
  background: var(--accent-soft);
}

.insight-tab span {
  display: grid;
  min-width: 18px;
  height: 18px;
  place-items: center;
  border-radius: 5px;
  background: rgba(31, 83, 98, 0.12);
  font-size: 11px;
}

.insight-body {
  max-height: 196px;
  overflow-y: auto;
  padding: 12px;
}

.context-card {
  display: grid;
  gap: 8px;
}

.context-card strong {
  display: block;
  font-size: 22px;
  letter-spacing: -0.04em;
}

.context-card span,
.token-grid span,
.insight-item span,
.insight-call span,
.insight-call small,
.insight-empty,
.attachment-drop span {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.5;
}

.token-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  background: var(--line);
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
}

.token-grid div,
.insight-item,
.insight-call,
.attachment-drop {
  border: 0;
  border-radius: 0;
  background: var(--surface);
}

.token-grid div {
  display: grid;
  gap: 5px;
  padding: 10px 12px;
}

.token-grid strong {
  font-size: 18px;
}

.insight-list {
  display: grid;
  gap: 1px;
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
}

.insight-item,
.insight-call {
  display: grid;
  gap: 3px;
  padding: 10px 12px;
}

.insight-call {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
}

.insight-call small {
  grid-column: 1 / -1;
}

.call-ok,
.call-fail {
  padding: 3px 6px;
  border-radius: 5px;
  font-size: 11px;
  font-style: normal;
  font-weight: 900;
}

.call-ok {
  color: var(--success);
  background: var(--success-soft);
}

.call-fail {
  color: var(--danger);
  background: var(--danger-soft);
}

.attachment-panel {
  display: grid;
  gap: 8px;
}

.attachment-drop {
  display: grid;
  gap: 6px;
  min-height: 88px;
  padding: 14px;
  border: 1px dashed var(--line-strong);
  border-radius: 10px;
  color: var(--ink);
  cursor: pointer;
}

.attachment-drop:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

.attachment-assets {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 6px;
}

.attachment-asset {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 8px 10px;
  text-align: left;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
  cursor: pointer;
}

.attachment-asset strong,
.attachment-asset span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attachment-asset span {
  color: var(--muted);
  font-size: 11px;
}

.attachment-asset--selected {
  color: var(--accent-deep);
  border-color: var(--accent);
  background: var(--accent-soft);
}

.attachment-asset:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.message-flow-enter-active,
.message-flow-leave-active,
.message-flow-move {
  transition: opacity var(--motion-med), transform var(--motion-med);
}

.message-flow-enter-from,
.message-flow-leave-to {
  opacity: 0;
  transform: translateY(10px) scale(0.99);
}

.insight-drawer-enter-active,
.insight-drawer-leave-active {
  transition: opacity var(--motion-med), transform var(--motion-med), max-height var(--motion-med);
}

.insight-drawer-enter-from,
.insight-drawer-leave-to {
  opacity: 0;
  transform: translateY(10px);
}

@keyframes caretPulse {
  0%,
  100% {
    opacity: 0.22;
  }

  50% {
    opacity: 0.9;
  }
}

@media (min-width: 841px) and (max-width: 1100px) {
  .chat-page {
    height: calc(100vh - 112px);
  }
}

@media (max-width: 840px) {
  .chat-page {
    height: auto;
    min-height: calc(100vh - 52px);
    overflow: visible;
  }

  .chat-workbench {
    grid-template-columns: 1fr;
    height: auto;
  }

  .session-rail {
    max-height: 174px;
  }

  .chat-commandbar,
  .runtime-controls {
    align-items: stretch;
    flex-direction: column;
  }

  .runtime-controls {
    width: 100%;
    overflow-x: auto;
  }

  .compact-field,
  .compact-field--wide {
    width: 100%;
    min-width: 0;
  }

  .chat-stage {
    min-height: calc(100vh - 246px);
  }
}

@media (max-width: 700px) {
  .chat-page {
    padding: 10px;
  }

  .chat-workbench {
    min-height: calc(100vh - 92px);
  }

  .message-list {
    padding: 18px 12px;
  }

  .message {
    max-width: 96%;
  }

  .token-grid {
    grid-template-columns: 1fr;
  }

  .composer {
    padding: 0 10px 10px;
  }
}
</style>
