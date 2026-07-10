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
          <button v-for="scope in scopes" :key="scope.value" :class="['button', { 'button--soft': toolStore.mcpScope === scope.value }]" type="button" @click="toolStore.loadMcps(scope.value)">
            {{ scope.label }}
          </button>
        </div>
        <span v-if="toolStore.errorMessage" class="error-text">{{ toolStore.errorMessage }}</span>
      </div>
      <table class="table">
        <thead>
          <tr>
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
            <td>
              <strong>{{ mcp.mcpName }}</strong>
              <small>{{ mcp.endpoint || mcp.description || '已配置服务端 stdio 命令' }}</small>
            </td>
            <td>{{ mcp.transportType }}</td>
            <td>{{ visibilityLabel(mcp.visibility) }}</td>
            <td>{{ mcp.currentVersion || '--' }} / {{ mcp.publishedVersion || '--' }}</td>
            <td>
              <span :class="['badge', testStatusClass(mcp.testStatus)]">{{ testStatusLabel(mcp.testStatus) }}</span>
              <small v-if="mcp.testMessage">{{ mcp.testMessage }}</small>
            </td>
            <td><span :class="['badge', statusClass(mcp.status)]">{{ statusLabel(mcp.status) }}</span></td>
            <td>
              <div class="button-row">
                <button class="button" type="button" @click="toolStore.testMcp(mcp.mcpId)">测试</button>
                <button class="button" type="button" :disabled="mcp.testStatus !== 'success' || toolStore.saving" @click="toolStore.publishMcp(mcp.mcpId, mcp.currentVersion)">发布</button>
                <button class="button" type="button" @click="toolStore.disableMcp(mcp.mcpId)">禁用</button>
              </div>
            </td>
          </tr>
          <tr v-if="toolStore.mcps.length === 0">
            <td colspan="7">暂无 MCP 配置，先创建一个 HTTP、SSE 或 stdio MCP。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive } from 'vue';

import SectionHeader from '@/components/common/SectionHeader.vue';
import { useToolStore } from '@/stores/tools';

const toolStore = useToolStore();
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
</style>
