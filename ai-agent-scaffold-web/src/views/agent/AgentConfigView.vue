<template>
  <div class="page agent-page">
    <SectionHeader
      title="Agent 管理"
      description="在当前租户与用户作用域内控制 Agent 可用性；共享基础配置不会被物理删除。"
    >
      <template #actions>
        <button class="button" type="button" :disabled="agentStore.loading" @click="reload">
          {{ agentStore.loading ? '刷新中…' : '刷新' }}
        </button>
      </template>
    </SectionHeader>

    <section class="agent-summary" aria-label="Agent 状态概览">
      <div><strong>{{ agentStore.agents.length }}</strong><span>配置总数</span></div>
      <div><strong>{{ agentStore.enabledCount }}</strong><span>已启用</span></div>
      <div><strong>{{ agentStore.disabledCount }}</strong><span>已禁用</span></div>
    </section>

    <section class="agent-list card">
      <div class="card__body">
        <article v-for="agent in agentStore.agents" :key="agent.agentId" class="agent-row">
          <div class="agent-mark">AI</div>
          <div class="agent-main">
            <div>
              <strong>{{ agent.agentName }}</strong>
              <span :class="['status-pill', agent.status === 'enabled' ? 'status-pill--enabled' : 'status-pill--disabled']">
                {{ agent.status === 'enabled' ? '已启用' : '已禁用' }}
              </span>
            </div>
            <p>{{ agent.agentDesc || '暂无 Agent 说明' }}</p>
            <small>{{ agent.agentId }} · {{ sourceLabel(agent.sourceType) }} · revision {{ agent.revision }}</small>
          </div>
          <div class="agent-actions">
            <button class="button button--soft" type="button"
                    :disabled="!agent.manageable || Boolean(agentStore.mutatingAgentId)"
                    @click="toggleAgent(agent)">
              {{ agentStore.mutatingAgentId === agent.agentId ? '处理中…' : agent.status === 'enabled' ? '禁用' : '启用' }}
            </button>
            <button class="button button--danger" type="button"
                    :disabled="!agent.manageable || agent.status === 'disabled' || Boolean(agentStore.mutatingAgentId)"
                    @click="removeAgent(agent)">
              删除（等同禁用）
            </button>
          </div>
        </article>

        <div v-if="!agentStore.loading && agentStore.agents.length === 0" class="empty-card">
          当前作用域没有可管理 Agent。
        </div>
      </div>
    </section>

    <p v-if="agentStore.errorMessage" class="error-text">{{ agentStore.errorMessage }}</p>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';

import SectionHeader from '@/components/common/SectionHeader.vue';
import { useAgentManagementStore } from '@/stores/agents';
import { useChatStore } from '@/stores/chat';
import type { AgentConfigItem } from '@/types/api';

const agentStore = useAgentManagementStore();
const chatStore = useChatStore();

onMounted(() => reload());

/**
 * 刷新 Agent 管理列表；无参数；错误由 Store 展示。
 */
async function reload() {
  try {
    await agentStore.loadAgents();
  } catch {
    // Store 已保存可展示的服务端错误。
  }
}

/**
 * 切换 Agent 状态；参数是 Agent；成功后刷新运行态列表。
 */
async function toggleAgent(agent: AgentConfigItem) {
  const enabled = agent.status !== 'enabled';
  if (!enabled && !window.confirm(`禁用“${agent.agentName}”后，新的聊天和定时执行将无法使用它。是否继续？`)) {
    return;
  }
  if (await agentStore.setEnabled(agent, enabled)) {
    await refreshRuntimeAgents();
  }
}

/**
 * 删除 Agent；参数是 Agent；确认后调用作用域禁用语义。
 */
async function removeAgent(agent: AgentConfigItem) {
  if (!window.confirm(`“${agent.agentName}”来自${sourceLabel(agent.sourceType)}。删除只会在当前作用域禁用，不会物理删除共享配置。是否继续？`)) {
    return;
  }
  if (await agentStore.remove(agent)) {
    await refreshRuntimeAgents();
  }
}

/**
 * 刷新聊天运行态 Agent；无参数；失败时保留管理操作成功事实并提示用户。
 */
async function refreshRuntimeAgents() {
  try {
    await chatStore.loadAgents();
  } catch {
    agentStore.errorMessage = 'Agent 状态已更新，但运行目标缓存刷新失败，进入聊天页后请手动刷新';
  }
}

/**
 * 转换 Agent 来源；参数是来源编码；返回中文说明。
 */
function sourceLabel(sourceType: AgentConfigItem['sourceType']) {
  return sourceType === 'static_config' ? '共享基础配置' : '数据库配置';
}
</script>

<style scoped>
.agent-page,
.agent-list .card__body {
  display: grid;
  gap: 14px;
}

.agent-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.agent-summary div {
  display: grid;
  gap: 4px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
}

.agent-summary strong {
  font-size: 24px;
}

.agent-summary span,
.agent-main p,
.agent-main small {
  color: var(--muted);
  font-size: 12px;
}

.agent-row {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
}

.agent-mark {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  color: var(--accent-deep);
  border-radius: 10px;
  background: var(--accent-soft);
  font-size: 12px;
  font-weight: 950;
}

.agent-main,
.agent-main div {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.agent-main {
  display: grid;
  gap: 5px;
}

.agent-main p {
  margin: 0;
}

.status-pill {
  padding: 3px 7px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 900;
}

.status-pill--enabled {
  color: var(--success);
  background: var(--success-soft);
}

.status-pill--disabled {
  color: var(--danger);
  background: var(--danger-soft);
}

.agent-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

@media (max-width: 720px) {
  .agent-summary {
    grid-template-columns: 1fr;
  }

  .agent-row {
    grid-template-columns: 44px minmax(0, 1fr);
  }

  .agent-actions {
    grid-column: 2;
    justify-content: flex-start;
  }
}
</style>
