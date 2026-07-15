<template>
  <div class="page asset-page">
    <SectionHeader
      title="附件资产"
      description="统一管理当前用户的聊天附件，可查看解析状态、来源会话并执行下载或软删除。"
    >
      <template #actions>
        <input ref="fileInputRef" class="visually-hidden" type="file" multiple
               accept=".txt,.md,.pdf,.doc,.docx,image/*" @change="onFileInput" />
        <button class="button button--soft" type="button" :disabled="assetStore.loading" @click="reload">
          {{ assetStore.loading ? '刷新中' : '刷新' }}
        </button>
        <button class="button button--primary" type="button" :disabled="assetStore.uploading" @click="openFilePicker">
          {{ assetStore.uploading ? '上传中…' : '上传文件' }}
        </button>
      </template>
    </SectionHeader>

    <section class="asset-summary" aria-label="资产概览">
      <div><strong>{{ assetStore.assets.length }}</strong><span>已加载资产</span></div>
      <div><strong>{{ readyCount }}</strong><span>可用附件</span></div>
      <div><strong>{{ formatFileSize(totalSize) }}</strong><span>已加载容量</span></div>
    </section>

    <section class="asset-card">
      <div class="asset-card__head">
        <div>
          <span>artifact_asset</span>
          <strong>聊天附件列表</strong>
        </div>
        <small>单文件最大 20 MiB，单次最多 10 个</small>
      </div>

      <div v-if="assetStore.assets.length > 0" class="asset-list">
        <article v-for="asset in assetStore.assets" :key="asset.assetId" class="asset-row">
          <div class="file-mark">{{ fileExtension(asset.fileName) }}</div>
          <div class="asset-main">
            <strong>{{ asset.fileName }}</strong>
            <span>{{ asset.mimeType || '未知类型' }} · {{ formatFileSize(asset.sizeBytes) }}</span>
          </div>
          <div class="asset-source">
            <span>来源会话</span>
            <strong>{{ asset.sessionId ? asset.sessionId.slice(0, 12) : '未绑定' }}</strong>
          </div>
          <div class="asset-status">
            <span :class="['status-pill', `status-pill--${asset.parseStatus}`]">
              {{ parseStatusLabel(asset.parseStatus) }}
            </span>
            <small>{{ formatDate(asset.createTime) }}</small>
          </div>
          <div class="asset-actions">
            <button class="button button--soft" type="button" @click="download(asset)">下载</button>
            <button class="button button--danger" type="button"
                    :disabled="assetStore.deletingAssetId === asset.assetId" @click="remove(asset.assetId, asset.fileName)">
              {{ assetStore.deletingAssetId === asset.assetId ? '删除中' : '删除' }}
            </button>
          </div>
        </article>
      </div>

      <div v-else-if="!assetStore.loading" class="asset-empty">
        <strong>还没有聊天附件</strong>
        <span>可在此处预先上传，或在会话的附件面板中绑定到具体会话。</span>
      </div>

      <div class="asset-footer">
        <span v-if="assetStore.errorMessage" class="error-text">{{ assetStore.errorMessage }}</span>
        <button v-if="assetStore.hasMore" class="button button--soft" type="button"
                :disabled="assetStore.loading" @click="loadMore">
          {{ assetStore.loading ? '加载中…' : '加载更多' }}
        </button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import SectionHeader from '@/components/common/SectionHeader.vue';
import { useAssetStore } from '@/stores/assets';
import type { ArtifactAsset } from '@/types/api';

const assetStore = useAssetStore();
const fileInputRef = ref<HTMLInputElement | null>(null);
const readyCount = computed(() => assetStore.assets.filter((asset) => asset.parseStatus === 'ready').length);
const totalSize = computed(() => assetStore.assets.reduce((sum, asset) => sum + (asset.sizeBytes || 0), 0));

onMounted(() => reload());

/**
 * 重新加载当前用户资产；无参数；错误由 Store 呈现。
 */
async function reload() {
  try {
    await assetStore.loadAssets('', true);
  } catch {
    // Store 已保存可展示的服务端错误。
  }
}

/**
 * 加载下一页资产；无参数；使用服务端游标。
 */
async function loadMore() {
  try {
    await assetStore.loadAssets('', false);
  } catch {
    // Store 已保存可展示的服务端错误。
  }
}

/**
 * 打开文件选择器；无参数。
 */
function openFilePicker() {
  fileInputRef.value?.click();
}

/**
 * 处理资产中心上传；参数是 input 事件；不自动选入任何聊天草稿。
 */
async function onFileInput(event: Event) {
  const input = event.target as HTMLInputElement;
  try {
    await assetStore.uploadFiles(Array.from(input.files || []), undefined, false);
  } catch (error) {
    assetStore.errorMessage = error instanceof Error ? error.message : '附件上传失败';
  } finally {
    input.value = '';
  }
}

/**
 * 下载资产；参数是资产元数据；通过认证请求获取 Blob。
 */
async function download(asset: ArtifactAsset) {
  try {
    await assetStore.download(asset);
  } catch (error) {
    assetStore.errorMessage = error instanceof Error ? error.message : '资产下载失败';
  }
}

/**
 * 软删除资产；参数是资产ID和文件名；确认后同步移除本地引用。
 */
async function remove(assetId: string, fileName: string) {
  if (!window.confirm(`确定删除附件“${fileName}”吗？已有审计记录会保留。`)) {
    return;
  }
  try {
    await assetStore.removeAsset(assetId);
  } catch {
    // Store 已保存可展示的服务端错误。
  }
}

/**
 * 格式化文件大小；参数是字节数；返回人类可读文本。
 */
function formatFileSize(sizeBytes: number) {
  if (sizeBytes < 1024) return `${sizeBytes} B`;
  if (sizeBytes < 1024 * 1024) return `${(sizeBytes / 1024).toFixed(1)} KiB`;
  return `${(sizeBytes / 1024 / 1024).toFixed(1)} MiB`;
}

/**
 * 格式化时间；参数是可选 ISO 时间；返回本地时间。
 */
function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN') : '--';
}

/**
 * 提取扩展名；参数是文件名；返回最多四个字符的标记。
 */
function fileExtension(fileName: string) {
  const extension = fileName.includes('.') ? fileName.split('.').pop() || 'FILE' : 'FILE';
  return extension.slice(0, 4).toUpperCase();
}

/**
 * 转换解析状态；参数是状态编码；返回中文展示。
 */
function parseStatusLabel(status: string) {
  return ({ ready: '可用', pending: '解析中', failed: '解析失败' } as Record<string, string>)[status] || status;
}
</script>

<style scoped>
.asset-page,
.asset-card,
.asset-list {
  display: grid;
  gap: 16px;
}

.asset-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.asset-summary div,
.asset-card {
  border: 1px solid var(--line);
  background: var(--surface);
}

.asset-summary div {
  display: grid;
  gap: 4px;
  padding: 16px;
  border-radius: 12px;
}

.asset-summary strong {
  font-size: 24px;
}

.asset-summary span,
.asset-card small,
.asset-row span,
.asset-status small,
.asset-empty span {
  color: var(--muted);
  font-size: 12px;
}

.asset-card {
  padding: 18px;
  border-radius: 14px;
}

.asset-card__head,
.asset-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.asset-card__head div,
.asset-main,
.asset-source,
.asset-status,
.asset-empty {
  display: grid;
  gap: 4px;
}

.asset-card__head span {
  color: var(--accent-deep);
  font-size: 11px;
  font-weight: 900;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.asset-list {
  gap: 1px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--line);
}

.asset-row {
  display: grid;
  grid-template-columns: 48px minmax(180px, 1.5fr) minmax(120px, 0.8fr) minmax(120px, 0.7fr) auto;
  align-items: center;
  gap: 14px;
  min-width: 0;
  padding: 12px;
  background: var(--surface);
}

.file-mark {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  color: var(--accent-deep);
  border-radius: 9px;
  background: var(--accent-soft);
  font-size: 10px;
  font-weight: 950;
}

.asset-main,
.asset-source {
  min-width: 0;
}

.asset-main strong,
.asset-main span,
.asset-source strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.asset-source strong {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.status-pill {
  width: fit-content;
  padding: 3px 7px;
  border-radius: 6px;
  color: var(--muted);
  background: var(--paper-soft);
  font-size: 11px;
  font-weight: 900;
}

.status-pill--ready {
  color: var(--success);
  background: var(--success-soft);
}

.status-pill--failed {
  color: var(--danger);
  background: var(--danger-soft);
}

.asset-actions {
  display: flex;
  gap: 6px;
}

.asset-empty {
  justify-items: center;
  padding: 48px 16px;
  text-align: center;
  border: 1px dashed var(--line-strong);
  border-radius: 10px;
}

.asset-footer {
  min-height: 34px;
}

.asset-footer .button {
  margin-left: auto;
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

@media (max-width: 980px) {
  .asset-row {
    grid-template-columns: 48px minmax(0, 1fr) auto;
  }

  .asset-source {
    grid-column: 2;
  }

  .asset-status {
    grid-column: 3;
    grid-row: 1 / span 2;
  }

  .asset-actions {
    grid-column: 2 / -1;
  }
}

@media (max-width: 620px) {
  .asset-summary {
    grid-template-columns: 1fr;
  }

  .asset-row {
    grid-template-columns: 44px minmax(0, 1fr);
  }

  .asset-status,
  .asset-actions {
    grid-column: 2;
    grid-row: auto;
  }
}
</style>
