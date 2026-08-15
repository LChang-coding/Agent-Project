<template>
  <div class="page page-grid">
    <SectionHeader
      title="MCP 中心"
      description="支持远程 HTTP/SSE 与服务端 stdio MCP。个人可发布私有 MCP，有权限的用户可发布企业公共 MCP。"
    >
      <template #actions>
        <div class="button-row">
          <button class="button" type="button" @click="toolStore.loadMcps(toolStore.mcpScope)">刷新 MCP</button>
          <button class="button button--primary" type="button" :disabled="toolStore.saving" @click="submitMcp">
            {{ toolStore.saving ? '保存中...' : '创建 MCP 草稿' }}
          </button>
        </div>
      </template>
    </SectionHeader>

    <section class="page-grid page-grid--two">
      <div class="card">
        <div class="card__body">
          <SectionHeader title="配置 MCP" description="HTTP/SSE 填远程地址；stdio 在服务端启动 MCP 进程后通过标准输入输出通信。" :level="2" />
          <div class="form-grid">
            <div class="field">
              <label>名称</label>
              <input v-model="form.mcpName" class="input" placeholder="例如：订单查询 MCP" />
            </div>
            <div class="field">
              <label>描述</label>
              <textarea v-model="form.description" class="textarea textarea--compact" placeholder="这个 MCP 提供哪些工具能力" />
            </div>
            <div class="field two-cols">
              <div>
                <label>可见范围</label>
                <select v-model="form.visibility" class="select">
                  <option value="private">个人私有</option>
                  <option value="tenant_public">企业公共</option>
                </select>
              </div>
              <div>
                <label>版本</label>
                <input v-model="form.version" class="input" placeholder="1.0.0" />
              </div>
            </div>
            <div class="field two-cols">
              <div>
                <label>传输类型</label>
                <select v-model="form.transportType" class="select">
                  <option value="http">HTTP</option>
                  <option value="sse">SSE</option>
                  <option value="stdio">STDIO（管理员）</option>
                  <option value="local" disabled>LOCAL（暂未开放）</option>
                </select>
              </div>
              <div v-if="form.transportType !== 'stdio'">
                <label>Endpoint</label>
                <input v-model="form.endpoint" class="input" placeholder="https://example.com/mcp" />
              </div>
              <div v-else class="field-hint">
                <strong>服务端 stdio</strong>
                <span>测试与调用都会由后端启动该命令，不需要填写 Endpoint。</span>
              </div>
            </div>
            <div v-if="form.transportType === 'stdio'" class="field">
              <label>启动命令</label>
              <input v-model="form.command" class="input" placeholder="输入服务端可执行命令" />
            </div>
            <div v-if="form.transportType === 'stdio'" class="field two-cols">
              <div>
                <label>启动参数（JSON 数组）</label>
                <textarea v-model="form.args" class="textarea textarea--compact" placeholder='["参数1", "参数2"]' />
              </div>
              <div>
                <label>环境变量（JSON 对象）</label>
                <textarea v-model="form.env" class="textarea textarea--compact" placeholder='{"KEY":"VALUE"}' />
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card__body">
          <SectionHeader title="运行说明" description="发布后的 MCP 不会触发工作流重建，下一轮会话由 ToolGateway 动态加载。" :level="2" />
          <div class="catalog-list">
            <div v-for="tool in toolStore.catalog.filter((item) => item.toolType === 'mcp')" :key="tool.toolId" class="catalog-item">
              <strong>{{ tool.toolName }}</strong>
              <span>{{ tool.version || '未发布' }} · {{ visibilityLabel(tool.visibility) }}</span>
            </div>
            <div v-if="toolStore.catalog.filter((item) => item.toolType === 'mcp').length === 0" class="empty-card">
              暂无可用 MCP。创建、测试并发布后，Agent 会自动拥有调用入口。
            </div>
          </div>
        </div>
      </div>
    </section>

    <div class="table-card">
      <div class="table-toolbar">
        <div class="button-row">
          <button v-for="scope in scopes" :key="scope.value" :class="['button', { 'button--soft': toolStore.mcpScope === scope.value }]" type="button" @click="loadMcps(scope.value)">
            {{ scope.label }}
          </button>
          <button class="button button--danger" type="button" :disabled="selectedMcpIds.size === 0 || batchDeleting" @click="batchDisableMcps">
            {{ batchDeleting ? '删除中…' : `批量删除${selectedMcpIds.size ? ` (${selectedMcpIds.size})` : ''}` }}
          </button>
        </div>
        <span v-if="batchFeedback" :class="['batch-feedback', { 'batch-feedback--error': batchFeedbackFailed }]" role="status" aria-live="polite">{{ batchFeedback }}</span>
        <span v-else-if="toolStore.errorMessage" class="error-text">{{ toolStore.errorMessage }}</span>
      </div>
      <div class="resource-table-scroll" tabindex="0" aria-label="MCP 资源列表，可横向滚动">
      <table class="table resource-table">
        <thead>
          <tr>
            <th class="selection-cell"><input type="checkbox" :checked="allSelectableMcpsSelected" aria-label="全选可删除 MCP" @change="toggleAllMcps" /></th>
            <th>名称</th>
            <th>类型</th>
            <th>范围</th>
            <th>版本</th>
            <th>测试</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="mcp in toolStore.mcps" :key="mcp.mcpId">
            <td class="selection-cell" data-label="选择"><input type="checkbox" :checked="selectedMcpIds.has(mcp.mcpId)"
                :disabled="mcp.status === 'disabled' || mcp.manageable === false || batchDeleting" :aria-label="`选择 MCP ${mcp.mcpName}`" @change="toggleMcpSelection(mcp.mcpId)" /></td>
            <td data-label="名称">
              <strong>{{ mcp.mcpName }}</strong>
              <small>{{ mcp.endpoint || mcp.description || '已配置服务端 stdio 命令' }}</small>
            </td>
            <td data-label="类型">{{ mcp.transportType }}</td>
            <td data-label="范围">{{ visibilityLabel(mcp.visibility) }}</td>
            <td data-label="版本">{{ mcp.currentVersion || '--' }} / {{ mcp.publishedVersion || '--' }}</td>
            <td data-label="测试">
              <span :class="['badge', testStatusClass(mcp.testStatus)]">{{ testStatusLabel(mcp.testStatus) }}</span>
              <small v-if="mcp.testMessage">{{ mcp.testMessage }}</small>
            </td>
            <td data-label="状态"><span :class="['badge', statusClass(mcp.status)]">{{ statusLabel(mcp.status) }}</span><small v-if="mcp.manageable === false">只读</small></td>
            <td class="resource-actions-cell" data-label="操作">
              <div class="button-row resource-actions">
                <button class="button" type="button" :disabled="mcp.manageable === false || isMcpPending(mcp.mcpId)" @click="runMcpAction('test', mcp.mcpId)">
                  {{ mcpOperationLabel(mcp.mcpId, 'test', '测试') }}
                </button>
                <button class="button" type="button" :disabled="mcp.manageable === false || mcp.testStatus !== 'success' || isMcpPending(mcp.mcpId)" @click="runMcpAction('publish', mcp.mcpId, mcp.currentVersion)">
                  {{ mcpOperationLabel(mcp.mcpId, 'publish', '发布') }}
                </button>
                <button class="button" type="button" :disabled="mcp.manageable === false || isMcpPending(mcp.mcpId)" @click="runMcpAction('disable', mcp.mcpId)">
                  {{ mcpOperationLabel(mcp.mcpId, 'disable', '禁用') }}
                </button>
              </div>
              <small
                v-if="mcpOperation(mcp.mcpId)?.errorMessage || mcpOperation(mcp.mcpId)?.successMessage"
                :class="['row-feedback', { 'row-feedback--error': mcpOperation(mcp.mcpId)?.errorMessage }]"
                role="status"
                aria-live="polite"
              >{{ mcpOperation(mcp.mcpId)?.errorMessage || mcpOperation(mcp.mcpId)?.successMessage }}</small>
            </td>
          </tr>
          <tr v-if="toolStore.mcps.length === 0">
            <td colspan="8">暂无 MCP 配置，先创建一个 HTTP、SSE 或 stdio MCP。</td>
          </tr>
        </tbody>
      </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';

import SectionHeader from '@/components/common/SectionHeader.vue';
import { executeBatchOperation } from '@/domain/tool-governance';
import { useToolStore } from '@/stores/tools';

const toolStore = useToolStore();
const selectedMcpIds = ref(new Set<string>());
const batchDeleting = ref(false);
const batchFeedback = ref('');
const batchFeedbackFailed = ref(false);
const selectableMcpIds = computed(() => toolStore.mcps
  .filter((mcp) => mcp.status !== 'disabled' && mcp.manageable !== false).map((mcp) => mcp.mcpId));
const allSelectableMcpsSelected = computed(() => selectableMcpIds.value.length > 0
  && selectableMcpIds.value.every((mcpId) => selectedMcpIds.value.has(mcpId)));
const scopes = [
  { value: 'available', label: '当前可用' },
  { value: 'mine', label: '我的 MCP' },
  { value: 'tenant', label: '企业公共' },
];

const form = reactive({
  mcpName: '',
  description: '',
  visibility: 'private' as 'private' | 'tenant_public',
  version: '1.0.0',
  transportType: 'http' as 'http' | 'sse' | 'stdio' | 'local',
  endpoint: '',
  command: '',
  args: '',
  env: '',
});

onMounted(async () => {
  await Promise.all([toolStore.loadMcps('available'), toolStore.loadCatalog()]);
});

/** 切换 MCP 查询范围并清理旧列表选中态。 */
async function loadMcps(scope: string) {
  selectedMcpIds.value = new Set();
  batchFeedback.value = '';
  await toolStore.loadMcps(scope);
}

/**
 * 创建 MCP；无参数；把当前表单提交为草稿。
 */
async function submitMcp() {
  await toolStore.createMcp({
    ...form,
    description: cleanText(form.description),
    version: cleanText(form.version),
    endpoint: cleanText(form.endpoint),
    command: cleanText(form.command),
    args: cleanText(form.args),
    env: cleanText(form.env),
  });
}

function mcpOperation(mcpId: string) {
  return toolStore.resourceOperation('mcp', mcpId);
}

function isMcpPending(mcpId: string) {
  return Boolean(mcpOperation(mcpId)?.pending);
}

function mcpOperationLabel(mcpId: string, type: 'test' | 'publish' | 'disable', fallback: string) {
  const operation = mcpOperation(mcpId);
  return operation?.pending && operation.type === type ? `${fallback}中…` : fallback;
}

async function runMcpAction(type: 'test' | 'publish' | 'disable', mcpId: string, version?: string) {
  try {
    if (type === 'test') {
      await toolStore.testMcp(mcpId);
    } else if (type === 'publish') {
      await toolStore.publishMcp(mcpId, version);
    } else {
      await toolStore.disableMcp(mcpId);
    }
  } catch {
    // Store 已保留行级错误，按钮解除锁定后可直接重试。
  }
}

function toggleMcpSelection(mcpId: string) {
  const next = new Set(selectedMcpIds.value);
  next.has(mcpId) ? next.delete(mcpId) : next.add(mcpId);
  selectedMcpIds.value = next;
}

function toggleAllMcps() {
  selectedMcpIds.value = allSelectableMcpsSelected.value ? new Set() : new Set(selectableMcpIds.value);
}

async function batchDisableMcps() {
  const ids = [...selectedMcpIds.value];
  if (!ids.length || !window.confirm(`确定批量删除选中的 ${ids.length} 个 MCP 吗？版本与调用审计会保留。`)) return;
  batchDeleting.value = true;
  batchFeedback.value = '';
  try {
    const result = await executeBatchOperation(ids, (id) => toolStore.disableMcp(id));
    selectedMcpIds.value = new Set(result.failedIds);
    batchFeedback.value = result.message;
    batchFeedbackFailed.value = result.failedIds.length > 0;
    toolStore.errorMessage = result.failedIds.length ? result.message : '';
  } finally {
    batchDeleting.value = false;
  }
}

/**
 * 清理可选文本；参数是输入值；返回非空文本或 undefined。
 */
function cleanText(value: string) {
  const text = value.trim();
  return text ? text : undefined;
}

/**
 * 可见范围展示；参数是范围编码；返回中文文案。
 */
function visibilityLabel(value: string) {
  return value === 'tenant_public' ? '企业公共' : '个人私有';
}

/**
 * 状态展示；参数是状态编码；返回中文文案。
 */
function statusLabel(value: string) {
  const map: Record<string, string> = {
    draft: '草稿',
    active: '已发布',
    disabled: '已禁用',
  };
  return map[value] || value;
}

/**
 * 状态样式；参数是状态编码；返回 badge 类名。
 */
function statusClass(value: string) {
  if (value === 'active') return 'badge--green';
  if (value === 'disabled') return 'badge--red';
  return 'badge--gold';
}

/**
 * 测试状态展示；参数是状态编码；返回中文文案。
 */
function testStatusLabel(value?: string) {
  const map: Record<string, string> = {
    success: '测试通过',
    failed: '测试失败',
    untested: '未测试',
  };
  return map[value || 'untested'] || value || '未测试';
}

/**
 * 测试状态样式；参数是状态编码；返回 badge 类名。
 */
function testStatusClass(value?: string) {
  if (value === 'success') return 'badge--green';
  if (value === 'failed') return 'badge--red';
  return 'badge--gold';
}
</script>

<style scoped>
.two-cols {
  grid-template-columns: 1fr 1fr;
}

.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 52px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
}

.catalog-list {
  display: grid;
  gap: 1px;
  margin-top: 14px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--line);
}

.catalog-item {
  display: grid;
  gap: 4px;
  padding: 11px 12px;
  background: var(--surface);
}

.catalog-item span,
.table small {
  display: block;
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.textarea--compact {
  min-height: 78px;
}

.field-hint {
  display: grid;
  align-content: center;
  gap: 4px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.field-hint strong {
  color: var(--ink);
  font-size: 13px;
}

.resource-table-scroll {
  overflow-x: auto;
  overscroll-behavior-x: contain;
}

.resource-table {
  min-width: 920px;
}
.selection-cell { width: 42px; text-align: center; }
.selection-cell input { width: 17px; height: 17px; accent-color: var(--accent-deep); }

.resource-actions-cell,
.resource-table th:last-child {
  position: sticky;
  right: 0;
  z-index: var(--z-sticky);
  background: var(--surface);
  box-shadow: -1px 0 0 var(--line);
}

.resource-actions {
  flex-wrap: nowrap;
}

.row-feedback {
  display: block;
  margin-top: 7px;
  color: var(--success);
  font-size: 12px;
  line-height: 1.45;
}

.row-feedback--error {
  color: var(--danger);
}
.batch-feedback { color: var(--success); font-size: 12px; font-weight: 700; }
.batch-feedback--error { color: var(--danger); }

@media (max-width: 768px) {
  .two-cols {
    grid-template-columns: 1fr;
  }

  .table-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .resource-table-scroll {
    overflow: visible;
  }

  .resource-table {
    display: block;
    min-width: 0;
  }

  .resource-table thead {
    display: none;
  }

  .resource-table tbody,
  .resource-table tr {
    display: grid;
  }

  .resource-table tbody {
    gap: 10px;
    padding: 10px;
  }

  .resource-table tr {
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    background: var(--surface);
  }

  .resource-table td {
    display: grid;
    grid-template-columns: minmax(82px, 0.34fr) minmax(0, 1fr);
    gap: 12px;
    padding: 9px 11px;
    overflow-wrap: anywhere;
  }

  .resource-table td::before {
    color: var(--muted);
    content: attr(data-label);
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.06em;
  }

  .resource-actions-cell {
    position: static;
    z-index: auto;
    box-shadow: none;
  }

  .resource-actions-cell::before {
    align-self: center;
  }

  .resource-actions {
    flex-wrap: wrap;
    width: 100%;
  }

  .resource-actions .button {
    flex: 1 1 72px;
  }

  .row-feedback {
    grid-column: 2;
  }
}
</style>
