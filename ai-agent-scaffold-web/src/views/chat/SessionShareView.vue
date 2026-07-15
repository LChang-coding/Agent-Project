<template>
  <main class="share-page">
    <section class="share-card">
      <span class="eyebrow">会话分享</span>
      <h1>{{ preview?.title || '正在读取分享...' }}</h1>
      <p v-if="preview" class="summary">
        共 {{ preview.messageCount }} 条有效消息 · {{ formatExpiry(preview.expiresAt) }}失效
      </p>
      <p v-if="errorMessage" class="error-banner">{{ errorMessage }}</p>
      <div v-if="loading" class="loading">正在验证分享令牌与文件状态…</div>
      <div v-else-if="preview">
        <section v-if="toolItems.length > 0" class="tool-check" aria-label="工具权限预检">
          <div class="tool-check__head">
            <strong>工具依赖预检</strong>
            <span :class="['risk-pill', { 'risk-pill--warn': hasToolRisk }]">
              {{ hasToolRisk ? `${missingToolCount} 项需要注意` : '权限齐备' }}
            </span>
          </div>
          <div class="tool-list">
            <div v-for="tool in toolItems" :key="`${tool.toolType}-${tool.toolId}-${tool.version || ''}`" class="tool-row">
              <div>
                <strong>{{ tool.toolName }}</strong>
                <span>{{ tool.toolType.toUpperCase() }} · {{ tool.version || '当前版本' }} · {{ tool.source }}</span>
              </div>
              <em :class="`tool-access tool-access--${tool.access}`">{{ accessLabel(tool.access) }}</em>
              <small v-if="tool.reason">{{ tool.reason }}</small>
            </div>
          </div>
          <label v-if="hasToolRisk" class="risk-confirm">
            <input v-model="confirmToolAccessRisk" type="checkbox" />
            <span>我已知道缺失或无权工具不会自动授权，导入后相关能力将保持不可执行。</span>
          </label>
        </section>

        <p v-else-if="preview.legacySnapshot" class="legacy-note">该分享来自旧版快照，未包含工具依赖证据。</p>

        <div class="actions">
          <button class="button button--primary" :disabled="importing || (hasToolRisk && !confirmToolAccessRisk)" @click="importCopy">
            {{ importing ? '导入中…' : '复制到我的会话' }}
          </button>
          <button class="button button--soft" :disabled="downloading" @click="downloadFile">
            {{ downloading ? '下载中…' : '下载聊天记录' }}
          </button>
        </div>
      </div>
      <p class="security-note">导入会创建独立副本，不会继承原用户权限、运行状态或无效消息。</p>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { downloadSessionShare, importSessionShare, previewSessionShare } from '@/api/share';
import { useChatStore } from '@/stores/chat';
import type { SessionShareResponse, ShareToolAccessItem } from '@/types/api';

const route = useRoute();
const router = useRouter();
const chatStore = useChatStore();
const token = String(route.params.token || '');
const preview = ref<SessionShareResponse | null>(null);
const loading = ref(true);
const importing = ref(false);
const downloading = ref(false);
const errorMessage = ref('');
const confirmToolAccessRisk = ref(false);
const toolItems = computed<ShareToolAccessItem[]>(() => preview.value?.toolPrecheck?.items || []);
const missingToolCount = computed(() => toolItems.value.filter((tool) => tool.access !== 'available').length);
const hasToolRisk = computed(() => Boolean(preview.value?.toolPrecheck?.hasRisk || missingToolCount.value > 0));

onMounted(async () => {
  try {
    preview.value = await previewSessionShare(token);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '分享不存在或已失效';
  } finally {
    loading.value = false;
  }
});

async function importCopy() {
  if (hasToolRisk.value && !confirmToolAccessRisk.value) {
    errorMessage.value = '请先确认缺失或无权工具风险';
    return;
  }
  importing.value = true;
  errorMessage.value = '';
  try {
    const result = await importSessionShare(token, {
      confirmToolAccessRisk: confirmToolAccessRisk.value,
    });
    await chatStore.acceptImportedSession(result);
    await router.push({ name: 'chat' });
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '导入失败';
  } finally {
    importing.value = false;
  }
}

async function downloadFile() {
  downloading.value = true;
  errorMessage.value = '';
  try {
    await downloadSessionShare(token);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '下载失败';
  } finally {
    downloading.value = false;
  }
}

function formatExpiry(value: string) {
  return new Date(value).toLocaleString('zh-CN');
}

/**
 * 转换工具权限状态；参数是预检状态；返回中文提示。
 */
function accessLabel(access: ShareToolAccessItem['access']) {
  return ({ available: '可用', missing: '未安装', denied: '无权限' })[access];
}
</script>

<style scoped>
.share-page { min-height: calc(100vh - 96px); display: grid; place-items: center; padding: 32px; }
.share-card { width: min(680px, 100%); padding: 38px; border: 1px solid var(--line); border-radius: 24px; background: var(--panel); box-shadow: var(--shadow); }
.eyebrow { color: var(--accent); font-size: 12px; letter-spacing: .16em; text-transform: uppercase; }
h1 { margin: 12px 0 8px; font-size: clamp(28px, 5vw, 44px); }
.summary, .security-note { color: var(--muted); }
.actions { display: flex; gap: 12px; margin: 28px 0; flex-wrap: wrap; }
.loading { padding: 28px 0; color: var(--muted); }
.error-banner { padding: 12px 14px; border-radius: 12px; color: #b42318; background: #fff1f0; }
.tool-check { display: grid; gap: 10px; margin-top: 24px; padding: 14px; border: 1px solid var(--line); border-radius: 12px; }
.tool-check__head, .tool-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.risk-pill, .tool-access { padding: 3px 7px; border-radius: 6px; color: var(--success); background: var(--success-soft); font-size: 11px; font-style: normal; font-weight: 900; }
.risk-pill--warn, .tool-access--missing, .tool-access--denied { color: var(--danger); background: var(--danger-soft); }
.tool-list { display: grid; gap: 1px; overflow: hidden; border: 1px solid var(--line); border-radius: 8px; background: var(--line); }
.tool-row { flex-wrap: wrap; padding: 9px 10px; background: var(--surface); }
.tool-row div { display: grid; gap: 2px; }
.tool-row span, .tool-row small, .legacy-note, .risk-confirm span { color: var(--muted); font-size: 11px; }
.tool-row small { flex-basis: 100%; }
.risk-confirm { display: flex; align-items: flex-start; gap: 8px; padding: 10px; border-radius: 8px; background: var(--danger-soft); cursor: pointer; }
.legacy-note { padding: 10px 12px; border-radius: 8px; background: var(--paper-soft); }
</style>
