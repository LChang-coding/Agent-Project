<template>
  <div class="page agent-page">
    <SectionHeader
      title="Agent 管理"
      description="在当前租户与用户作用域内控制 Agent 可用性；共享基础配置不会被物理删除。"
    >
      <template #actions>
        <div class="button-row">
          <button class="button" type="button" :disabled="agentStore.loading" @click="reload">
            {{ agentStore.loading ? '刷新中…' : '刷新' }}
          </button>
          <button class="button button--danger" type="button" :disabled="selectedAgentIds.size === 0 || batchDeleting" @click="batchRemoveAgents">
            {{ batchDeleting ? '删除中…' : `批量删除${selectedAgentIds.size ? ` (${selectedAgentIds.size})` : ''}` }}
          </button>
        </div>
      </template>
    </SectionHeader>

    <section class="agent-summary" aria-label="Agent 状态概览">
      <div><strong>{{ agentStore.agents.length }}</strong><span>配置总数</span></div>
      <div><strong>{{ agentStore.enabledCount }}</strong><span>已启用</span></div>
      <div><strong>{{ agentStore.disabledCount }}</strong><span>已禁用</span></div>
    </section>

    <section class="agent-list card">
      <div class="card__body">
        <div v-if="agentStore.agents.length" class="batch-toolbar">
          <label><input type="checkbox" :checked="allRemovableAgentsSelected" @change="toggleAllAgents">全选可管理 Agent</label>
          <span>删除会在当前作用域内禁用配置，保留共享定义与审计。</span>
        </div>
        <article v-for="agent in agentStore.agents" :key="agent.agentId" class="agent-row">
          <input class="batch-checkbox" type="checkbox" :checked="selectedAgentIds.has(agent.agentId)"
                 :disabled="!agent.manageable || batchDeleting" :aria-label="`选择 Agent ${agent.agentName}`"
                 @change="toggleAgentSelection(agent.agentId)" />
          <div class="agent-mark">AI</div>
          <div class="agent-main">
            <div>
              <strong>{{ agent.agentName }}</strong>
              <span :class="['status-pill', agent.status === 'enabled' ? 'status-pill--enabled' : 'status-pill--disabled']">
                {{ agent.status === 'enabled' ? '已启用' : '已禁用' }}
              </span>
            </div>
            <p>{{ agent.agentDesc || '暂无 Agent 说明' }}</p>
            <div class="agent-tags">
              <span class="metadata-pill">{{ agent.orchestrationRole === 'SUPERVISOR' ? '主 Agent' : '执行 Agent' }}</span>
              <span v-if="agent.category" class="metadata-pill">{{ agent.category }}</span>
              <span v-for="capability in agent.capabilities || []" :key="capability" class="metadata-pill">
                {{ capability }}
              </span>
            </div>
            <small v-if="agent.bestFor?.length">适合：{{ agent.bestFor.join('、') }}</small>
            <small v-if="agent.allowedSubAgentIds?.length">
              可委派模板：{{ agent.allowedSubAgentIds.join('、') }}
            </small>
            <small>{{ agent.agentId }} · {{ sourceLabel(agent.sourceType) }} · revision {{ agent.revision }}</small>
            <details v-if="agent.toolPermissions?.length" class="tool-permissions">
              <summary>
                <span>工具执行权限</span>
                <small>{{ agent.toolPermissions.length }} 项可配置</small>
              </summary>
              <div class="permission-grid">
                <article v-for="permission in agent.toolPermissions" :key="permission.toolCode" class="tool-permission">
                  <header>
                    <div>
                      <span class="tool-kind">{{ toolTypeLabel(permission.toolType) }}</span>
                      <strong>{{ permission.toolName || permission.toolCode }}</strong>
                    </div>
                    <code>{{ permission.toolCode }}</code>
                  </header>
                  <p>{{ permission.description || '该工具未提供用途说明' }}</p>
                  <label>
                    执行策略
                    <select v-model="permission.mode" :disabled="!agent.manageable">
                      <option value="ALLOW">直接允许</option>
                      <option value="REQUIRE_APPROVAL">每次需要人工审批</option>
                      <option value="DENY">禁止调用</option>
                    </select>
                  </label>
                  <div v-if="permission.mode === 'REQUIRE_APPROVAL'" class="approval-policy">
                    <label>超时秒数<input v-model.number="permission.timeoutSeconds" type="number" min="60" max="3600"></label>
                    <label>超时默认<select v-model="permission.timeoutDecision"><option value="REJECT">默认拒绝</option><option value="APPROVE">默认同意</option></select></label>
                    <label class="approval-suggestions">审批建议（每行一项）
                      <textarea :value="permission.suggestions.join('\n')" :disabled="!agent.manageable"
                                @input="updateSuggestions(permission, $event)"></textarea>
                    </label>
                  </div>
                  <button class="button button--soft" type="button"
                          :disabled="!agent.manageable || Boolean(agentStore.mutatingPermissionAgentId)"
                          @click="savePermission(agent, permission)">
                    {{ agentStore.mutatingPermissionAgentId === permissionKey(agent, permission) ? '保存中…' : '保存该工具策略' }}
                  </button>
                </article>
              </div>
            </details>
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
import { computed, onMounted, ref } from 'vue';

import SectionHeader from '@/components/common/SectionHeader.vue';
import { useAgentManagementStore } from '@/stores/agents';
import { useChatStore } from '@/stores/chat';
import type { AgentConfigItem, AgentToolPermission } from '@/types/api';

const agentStore = useAgentManagementStore();
const chatStore = useChatStore();
const selectedAgentIds = ref(new Set<string>());
const batchDeleting = ref(false);
const removableAgentIds = computed(() => agentStore.agents.filter((agent) => agent.manageable).map((agent) => agent.agentId));
const allRemovableAgentsSelected = computed(() => removableAgentIds.value.length > 0
  && removableAgentIds.value.every((agentId) => selectedAgentIds.value.has(agentId)));

onMounted(() => reload());

/**
 * 刷新 Agent 管理列表；无参数；错误由 Store 展示。
 */
async function reload() {
  try {
    await agentStore.loadAgents();
    selectedAgentIds.value = new Set([...selectedAgentIds.value].filter((agentId) => agentStore.agents.some((agent) => agent.agentId === agentId)));
  } catch {
    // Store 已保存可展示的服务端错误。
  }
}

function toggleAgentSelection(agentId: string) {
  const next = new Set(selectedAgentIds.value);
  next.has(agentId) ? next.delete(agentId) : next.add(agentId);
  selectedAgentIds.value = next;
}

function toggleAllAgents() {
  selectedAgentIds.value = allRemovableAgentsSelected.value ? new Set() : new Set(removableAgentIds.value);
}

async function batchRemoveAgents() {
  const targets = agentStore.agents.filter((agent) => selectedAgentIds.value.has(agent.agentId) && agent.manageable);
  if (!targets.length || !window.confirm(`确定批量删除选中的 ${targets.length} 个 Agent 吗？该操作等同于在当前作用域禁用。`)) return;
  batchDeleting.value = true;
  let failed = 0;
  for (const agent of targets) if (!(await agentStore.remove(agent))) failed += 1;
  selectedAgentIds.value = new Set();
  batchDeleting.value = false;
  await refreshRuntimeAgents();
  if (failed) agentStore.errorMessage = `${targets.length - failed} 个 Agent 已删除，${failed} 个处理失败，请刷新后重试`;
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

function updateSuggestions(permission: AgentToolPermission, event: Event) {
  permission.suggestions = (event.target as HTMLTextAreaElement).value.split(/\r?\n/).slice(0, 8);
}

async function savePermission(agent: AgentConfigItem, permission: AgentToolPermission) {
  await agentStore.saveToolPermission(agent, permission);
}

function permissionKey(agent: AgentConfigItem, permission: AgentToolPermission) {
  return `${agent.agentId}:${permission.toolCode}`;
}

function toolTypeLabel(type?: AgentToolPermission['toolType']) {
  return type === 'mcp' ? 'MCP' : type === 'skill' ? 'SKILL' : '平台';
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
  grid-template-columns: 24px 48px minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
}
.batch-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border: 1px solid var(--line); background: var(--surface-soft); color: var(--muted); font-size: 12px; }
.batch-toolbar label { display: flex; align-items: center; gap: 7px; color: var(--ink); font-weight: 800; }
.batch-checkbox { width: 17px; height: 17px; accent-color: var(--accent-deep); }

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

.agent-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.metadata-pill {
  padding: 2px 7px;
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--muted);
  font-size: 11px;
}

.agent-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.tool-permissions {
  margin-top: 8px;
  border: 1px solid var(--line);
  background: var(--surface-soft);
}

.tool-permissions summary {
  display: flex;
  justify-content: space-between;
  padding: 12px 14px;
  cursor: pointer;
  font-weight: 850;
}

.tool-permissions summary small { color: var(--muted); font-weight: 600; }
.permission-grid { display: grid !important; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px !important; padding: 0 12px 12px; }
.tool-permission { display: grid !important; align-content: start; gap: 10px !important; padding: 14px; border: 1px solid var(--line); background: var(--surface); }
.tool-permission header { display: grid !important; gap: 6px !important; }
.tool-permission header > div { display: flex; align-items: center; gap: 8px; }
.tool-permission header code { overflow: hidden; color: var(--muted); font-size: 10px; text-overflow: ellipsis; }
.tool-permission > p { min-height: 34px; color: var(--muted); font-size: 12px; line-height: 1.45; }
.tool-kind { padding: 3px 6px; border: 1px solid var(--line); color: var(--accent-deep); font-size: 9px; font-weight: 900; letter-spacing: .1em; }
.tool-permission label {
  display: grid;
  gap: 4px;
  color: var(--muted);
  font-size: 12px;
}
.tool-permission select,.tool-permission input,.tool-permission textarea { box-sizing: border-box; width: 100%; min-height: 38px; border: 1px solid var(--line); background: #fff; color: var(--ink); }
.tool-permission textarea { min-height: 78px; padding: 8px; resize: vertical; }
.approval-policy { display: grid !important; grid-template-columns: 1fr 1fr; gap: 8px !important; }
.approval-suggestions { grid-column: 1/-1; }

@media (max-width: 720px) {
  .agent-summary {
    grid-template-columns: 1fr;
  }

  .agent-row {
    grid-template-columns: 22px 44px minmax(0, 1fr);
  }

  .agent-actions {
    grid-column: 3;
    justify-content: flex-start;
  }

  .permission-grid { grid-template-columns: 1fr; }
  .batch-toolbar { align-items: flex-start; flex-direction: column; }
}
</style>
