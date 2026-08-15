<template>
  <div class="page page-grid">
    <SectionHeader
      title="Skill 中心"
      description="Skill 第一版作为可版本化的 SKILL.md 指令包发布，包文件进入对象存储，权限和版本状态进入 MySQL。"
    >
      <template #actions>
        <div class="button-row">
          <button class="button" type="button" @click="toolStore.loadCatalog">刷新工具目录</button>
          <button class="button button--primary" type="button" :disabled="toolStore.saving" @click="submitSkill">
            {{ toolStore.saving ? '发布中...' : '创建 Skill 草稿' }}
          </button>
        </div>
      </template>
    </SectionHeader>

    <section class="page-grid page-grid--two">
      <div class="card">
        <div class="card__body">
          <SectionHeader title="发布 Skill" description="上传 zip，必须包含 SKILL.md；企业公共 Skill 需要 owner/admin 权限。" :level="2" />
          <div class="form-grid">
            <div class="field">
              <label>Skill 包</label>
              <input class="input" type="file" accept=".zip,application/zip" @change="onFileChanged" />
              <small v-if="selectedFile">{{ selectedFile.name }}</small>
            </div>
            <div class="field">
              <label>名称</label>
              <input v-model="form.skillName" class="input" placeholder="例如：日报写作助手" />
            </div>
            <div class="field">
              <label>编码</label>
              <input v-model="form.skillCode" class="input" placeholder="daily_report_writer" />
            </div>
            <div class="field">
              <label>描述</label>
              <textarea v-model="form.description" class="textarea textarea--compact" placeholder="告诉 Agent 这个 Skill 擅长什么" />
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
          </div>
          <p v-if="uploadHint" class="hint-text">{{ uploadHint }}</p>
        </div>
      </div>

      <div class="card">
        <div class="card__body">
          <SectionHeader title="运行时策略" description="工作流节点不再固化工具，Agent 每轮会自动加载当前用户有权限的 Skill/MCP。" :level="2" />
          <div class="catalog-list">
            <div v-for="tool in toolStore.catalog" :key="`${tool.toolType}-${tool.toolId}`" class="catalog-item">
              <strong>{{ tool.toolName }}</strong>
              <span>{{ tool.toolType }} · {{ tool.version || '未发布' }} · {{ visibilityLabel(tool.visibility) }}</span>
            </div>
            <div v-if="toolStore.catalog.length === 0" class="empty-card">暂无可用工具。发布并激活 Skill 后，下轮对话会自动加载。</div>
          </div>
        </div>
      </div>
    </section>

    <div class="table-card">
      <div class="table-toolbar">
        <div class="button-row">
          <button v-for="scope in scopes" :key="scope.value" :class="['button', { 'button--soft': toolStore.skillScope === scope.value }]" type="button" @click="loadSkills(scope.value)">
            {{ scope.label }}
          </button>
          <button class="button button--danger" type="button" :disabled="selectedSkillIds.size === 0 || batchDeleting" @click="batchDisableSkills">
            {{ batchDeleting ? '删除中…' : `批量删除${selectedSkillIds.size ? ` (${selectedSkillIds.size})` : ''}` }}
          </button>
        </div>
        <span v-if="batchFeedback" :class="['batch-feedback', { 'batch-feedback--error': batchFeedbackFailed }]" role="status" aria-live="polite">{{ batchFeedback }}</span>
        <span v-else-if="toolStore.errorMessage" class="error-text">{{ toolStore.errorMessage }}</span>
      </div>
      <div class="resource-table-scroll" tabindex="0" aria-label="Skill 资源列表，可横向滚动">
      <table class="table resource-table">
        <thead>
          <tr>
            <th class="selection-cell"><input type="checkbox" :checked="allSelectableSkillsSelected" aria-label="全选可删除 Skill" @change="toggleAllSkills" /></th>
            <th>名称</th>
            <th>编码</th>
            <th>范围</th>
            <th>当前版本</th>
            <th>发布版本</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="skill in toolStore.skills" :key="skill.skillId">
            <td class="selection-cell" data-label="选择"><input type="checkbox" :checked="selectedSkillIds.has(skill.skillId)"
                :disabled="skill.status === 'disabled' || skill.manageable === false || batchDeleting" :aria-label="`选择 Skill ${skill.skillName}`" @change="toggleSkillSelection(skill.skillId)" /></td>
            <td data-label="名称">
              <strong>{{ skill.skillName }}</strong>
              <small>{{ skill.description || '暂无描述' }}</small>
            </td>
            <td data-label="编码">{{ skill.skillCode }}</td>
            <td data-label="范围">{{ visibilityLabel(skill.visibility) }}</td>
            <td data-label="当前版本">{{ skill.currentVersion || '--' }}</td>
            <td data-label="发布版本">{{ skill.publishedVersion || '--' }}</td>
            <td data-label="状态"><span :class="['badge', statusClass(skill.status)]">{{ statusLabel(skill.status) }}</span><small v-if="skill.manageable === false">只读</small></td>
            <td class="resource-actions-cell" data-label="操作">
              <div class="button-row resource-actions">
                <button class="button" type="button" :disabled="skill.manageable === false || isSkillPending(skill.skillId)" @click="runSkillAction('publish', skill.skillId, skill.currentVersion)">
                  {{ skillOperationLabel(skill.skillId, 'publish', '发布') }}
                </button>
                <button class="button" type="button" :disabled="skill.manageable === false || isSkillPending(skill.skillId)" @click="runSkillAction('disable', skill.skillId)">
                  {{ skillOperationLabel(skill.skillId, 'disable', '禁用') }}
                </button>
              </div>
              <small
                v-if="skillOperation(skill.skillId)?.errorMessage || skillOperation(skill.skillId)?.successMessage"
                :class="['row-feedback', { 'row-feedback--error': skillOperation(skill.skillId)?.errorMessage }]"
                role="status"
                aria-live="polite"
              >{{ skillOperation(skill.skillId)?.errorMessage || skillOperation(skill.skillId)?.successMessage }}</small>
            </td>
          </tr>
          <tr v-if="toolStore.skills.length === 0">
            <td colspan="8">暂无 Skill，先上传一个包含 SKILL.md 的 zip。</td>
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
const selectedFile = ref<File | null>(null);
const uploadHint = ref('');
const selectedSkillIds = ref(new Set<string>());
const batchDeleting = ref(false);
const batchFeedback = ref('');
const batchFeedbackFailed = ref(false);
const selectableSkillIds = computed(() => toolStore.skills
  .filter((skill) => skill.status !== 'disabled' && skill.manageable !== false).map((skill) => skill.skillId));
const allSelectableSkillsSelected = computed(() => selectableSkillIds.value.length > 0
  && selectableSkillIds.value.every((skillId) => selectedSkillIds.value.has(skillId)));
const scopes = [
  { value: 'available', label: '当前可用' },
  { value: 'mine', label: '我的 Skill' },
  { value: 'tenant', label: '企业公共' },
];

const form = reactive({
  skillName: '',
  skillCode: '',
  description: '',
  visibility: 'private' as 'private' | 'tenant_public',
  version: '1.0.0',
});

onMounted(async () => {
  await Promise.all([toolStore.loadSkills('available'), toolStore.loadCatalog()]);
});

/**
 * 文件变化处理；参数是表单事件；保存当前选择的 zip 文件。
 */
function onFileChanged(event: Event) {
  const input = event.target as HTMLInputElement;
  selectedFile.value = input.files?.[0] || null;
}

/**
 * 创建 Skill；无参数；先上传文件再创建草稿。
 */
async function submitSkill() {
  if (!selectedFile.value) {
    uploadHint.value = '请先选择一个包含 SKILL.md 的 zip 包。';
    return;
  }
  const asset = await toolStore.uploadSkillPackage(selectedFile.value);
  await toolStore.createSkill({
    skillName: form.skillName || selectedFile.value.name.replace(/\.zip$/i, ''),
    skillCode: form.skillCode,
    description: form.description,
    visibility: form.visibility,
    version: form.version || '1.0.0',
    assetId: asset.assetId,
  });
  uploadHint.value = `已创建草稿：${asset.fileName} / ${asset.sha256}`;
}

/**
 * 加载 Skill；参数是范围；刷新列表。
 */
async function loadSkills(scope: string) {
  selectedSkillIds.value = new Set();
  batchFeedback.value = '';
  await toolStore.loadSkills(scope);
}

function skillOperation(skillId: string) {
  return toolStore.resourceOperation('skill', skillId);
}

function isSkillPending(skillId: string) {
  return Boolean(skillOperation(skillId)?.pending);
}

function skillOperationLabel(skillId: string, type: 'publish' | 'disable', fallback: string) {
  const operation = skillOperation(skillId);
  return operation?.pending && operation.type === type ? `${fallback}中…` : fallback;
}

async function runSkillAction(type: 'publish' | 'disable', skillId: string, version?: string) {
  try {
    if (type === 'publish') {
      await toolStore.publishSkill(skillId, version);
    } else {
      await toolStore.disableSkill(skillId);
    }
  } catch {
    // Store 已保留行级错误，按钮解除锁定后可直接重试。
  }
}

function toggleSkillSelection(skillId: string) {
  const next = new Set(selectedSkillIds.value);
  next.has(skillId) ? next.delete(skillId) : next.add(skillId);
  selectedSkillIds.value = next;
}

function toggleAllSkills() {
  selectedSkillIds.value = allSelectableSkillsSelected.value ? new Set() : new Set(selectableSkillIds.value);
}

async function batchDisableSkills() {
  const ids = [...selectedSkillIds.value];
  if (!ids.length || !window.confirm(`确定批量删除选中的 ${ids.length} 个 Skill 吗？版本与调用审计会保留。`)) return;
  batchDeleting.value = true;
  batchFeedback.value = '';
  try {
    const result = await executeBatchOperation(ids, (id) => toolStore.disableSkill(id));
    selectedSkillIds.value = new Set(result.failedIds);
    batchFeedback.value = result.message;
    batchFeedbackFailed.value = result.failedIds.length > 0;
    toolStore.errorMessage = result.failedIds.length ? result.message : '';
  } finally {
    batchDeleting.value = false;
  }
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
.hint-text,
.table small {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.table td:first-child {
  display: grid;
  gap: 4px;
}

.textarea--compact {
  min-height: 78px;
}

.resource-table-scroll {
  overflow-x: auto;
  overscroll-behavior-x: contain;
}

.resource-table {
  min-width: 850px;
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
