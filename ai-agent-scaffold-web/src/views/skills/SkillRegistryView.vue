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
        </div>
        <span v-if="toolStore.errorMessage" class="error-text">{{ toolStore.errorMessage }}</span>
      </div>
      <table class="table">
        <thead>
          <tr>
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
            <td>
              <strong>{{ skill.skillName }}</strong>
              <small>{{ skill.description || '暂无描述' }}</small>
            </td>
            <td>{{ skill.skillCode }}</td>
            <td>{{ visibilityLabel(skill.visibility) }}</td>
            <td>{{ skill.currentVersion || '--' }}</td>
            <td>{{ skill.publishedVersion || '--' }}</td>
            <td><span :class="['badge', statusClass(skill.status)]">{{ statusLabel(skill.status) }}</span></td>
            <td>
              <div class="button-row">
                <button class="button" type="button" @click="toolStore.publishSkill(skill.skillId, skill.currentVersion)">发布</button>
                <button class="button" type="button" @click="toolStore.disableSkill(skill.skillId)">禁用</button>
              </div>
            </td>
          </tr>
          <tr v-if="toolStore.skills.length === 0">
            <td colspan="7">暂无 Skill，先上传一个包含 SKILL.md 的 zip。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';

import SectionHeader from '@/components/common/SectionHeader.vue';
import { useToolStore } from '@/stores/tools';

const toolStore = useToolStore();
const selectedFile = ref<File | null>(null);
const uploadHint = ref('');
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
  await toolStore.loadSkills(scope);
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
</style>
