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
      <div v-else-if="preview" class="actions">
        <button class="button button--primary" :disabled="importing" @click="importCopy">
          {{ importing ? '导入中…' : '复制到我的会话' }}
        </button>
        <button class="button button--soft" :disabled="downloading" @click="downloadFile">
          {{ downloading ? '下载中…' : '下载聊天记录' }}
        </button>
      </div>
      <p class="security-note">导入会创建独立副本，不会继承原用户权限、运行状态或无效消息。</p>
    </section>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { downloadSessionShare, importSessionShare, previewSessionShare } from '@/api/share';
import { useChatStore } from '@/stores/chat';
import type { SessionShareResponse } from '@/types/api';

const route = useRoute();
const router = useRouter();
const chatStore = useChatStore();
const token = String(route.params.token || '');
const preview = ref<SessionShareResponse | null>(null);
const loading = ref(true);
const importing = ref(false);
const downloading = ref(false);
const errorMessage = ref('');

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
  importing.value = true;
  errorMessage.value = '';
  try {
    const result = await importSessionShare(token);
    chatStore.acceptImportedSession(result);
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
</style>
