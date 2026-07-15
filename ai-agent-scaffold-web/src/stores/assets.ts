import { defineStore } from 'pinia';

import { deleteAsset, downloadAsset, queryAssets, uploadChatAttachment } from '@/api/assets';
import type { ArtifactAsset } from '@/types/api';

const MAX_FILE_SIZE = 20 * 1024 * 1024;
const MAX_FILES_PER_BATCH = 10;
let listRequestGeneration = 0;

interface AssetState {
  assets: ArtifactAsset[];
  selectedAssets: ArtifactAsset[];
  selectionScope: string;
  listSessionId: string;
  nextCursor: string;
  hasMore: boolean;
  loading: boolean;
  uploading: boolean;
  deletingAssetId: string;
  errorMessage: string;
}

/**
 * 聊天附件与资产 Store。
 * <p>负责上传、分页、下载、删除和按会话隔离待发送附件。</p>
 */
export const useAssetStore = defineStore('assets', {
  state: (): AssetState => ({
    assets: [],
    selectedAssets: [],
    selectionScope: '',
    listSessionId: '',
    nextCursor: '',
    hasMore: false,
    loading: false,
    uploading: false,
    deletingAssetId: '',
    errorMessage: '',
  }),
  getters: {
    readySelectedAssets: (state) => state.selectedAssets.filter(isReadyAsset),
    selectedAssetIds(): string[] {
      return this.readySelectedAssets.map((asset) => asset.assetId);
    },
  },
  actions: {
    /**
     * 切换附件草稿范围；参数是会话或新会话范围；避免把旧会话选择带入新会话。
     */
    setSelectionScope(scope: string) {
      if (this.selectionScope === scope) {
        return;
      }
      this.selectionScope = scope;
      this.selectedAssets = [];
      this.errorMessage = '';
    },

    /**
     * 清空当前资产列表；无参数；用于尚未创建会话的聊天草稿隔离。
     */
    clearList() {
      listRequestGeneration += 1;
      this.assets = [];
      this.listSessionId = '';
      this.nextCursor = '';
      this.hasMore = false;
      this.loading = false;
    },

    /**
     * 查询资产；参数是可选会话和是否重置；返回当前已加载列表。
     */
    async loadAssets(sessionId = '', reset = true) {
      if (this.loading && !reset) {
        return this.assets;
      }
      const generation = reset ? ++listRequestGeneration : listRequestGeneration;
      if (reset) {
        this.listSessionId = sessionId;
        this.assets = [];
        this.nextCursor = '';
        this.hasMore = false;
      }
      this.loading = true;
      this.errorMessage = '';
      try {
        const page = await queryAssets(reset ? undefined : this.nextCursor || undefined, 50, sessionId || undefined);
        if (generation !== listRequestGeneration || this.listSessionId !== sessionId) {
          return this.assets;
        }
        this.assets = reset ? page.items : mergeAssets(this.assets, page.items);
        this.nextCursor = page.nextCursor || '';
        this.hasMore = Boolean(page.hasMore && page.nextCursor);
        return this.assets;
      } catch (error) {
        if (generation === listRequestGeneration) {
          this.errorMessage = error instanceof Error ? error.message : '资产列表加载失败';
        }
        throw error;
      } finally {
        if (generation === listRequestGeneration) {
          this.loading = false;
        }
      }
    },

    /**
     * 上传附件；参数是文件列表、可选会话和是否选入聊天；返回成功上传的资产。
     */
    async uploadFiles(files: File[], sessionId?: string, selectWhenReady = true) {
      const accepted = validateFiles(files);
      const scope = this.selectionScope;
      this.uploading = true;
      this.errorMessage = '';
      try {
        const results = await Promise.allSettled(accepted.map((file) => uploadChatAttachment(file, sessionId)));
        const uploaded = results
          .filter((result): result is PromiseFulfilledResult<ArtifactAsset> => result.status === 'fulfilled')
          .map((result) => result.value);
        if (this.listSessionId === (sessionId || '')) {
          this.assets = mergeAssets(uploaded, this.assets);
        }
        if (selectWhenReady && this.selectionScope === scope) {
          const remaining = Math.max(0, MAX_FILES_PER_BATCH - this.selectedAssets.length);
          this.selectedAssets = mergeAssets(this.selectedAssets, uploaded.filter(isReadyAsset).slice(0, remaining));
        }
        const failures = results.filter((result) => result.status === 'rejected').length;
        if (failures > 0) {
          this.errorMessage = `${failures} 个文件上传失败，其他文件已保留`;
        }
        return uploaded;
      } finally {
        this.uploading = false;
      }
    },

    /**
     * 切换待发送附件；参数是资产；只允许 ready 资产进入请求。
     */
    toggleSelected(asset: ArtifactAsset) {
      if (!isReadyAsset(asset)) {
        this.errorMessage = '附件尚未解析完成，暂不能发送';
        return;
      }
      if (this.selectedAssets.some((item) => item.assetId === asset.assetId)) {
        this.selectedAssets = this.selectedAssets.filter((item) => item.assetId !== asset.assetId);
        return;
      }
      if (this.selectedAssets.length >= MAX_FILES_PER_BATCH) {
        this.errorMessage = `单次最多选择 ${MAX_FILES_PER_BATCH} 个附件`;
        return;
      }
      this.selectedAssets = [...this.selectedAssets, asset];
    },

    /**
     * 判断资产是否已选；参数是资产ID；返回选中状态。
     */
    isSelected(assetId: string) {
      return this.selectedAssets.some((item) => item.assetId === assetId);
    },

    /**
     * 清空待发送附件；无参数；不删除服务端资产。
     */
    clearSelected() {
      this.selectedAssets = [];
    },

    /**
     * 下载资产；参数是资产；通过临时 Blob 地址触发保存。
     */
    async download(asset: ArtifactAsset) {
      const blob = await downloadAsset(asset.assetId);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = asset.fileName || 'attachment';
      anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 0);
    },

    /**
     * 删除资产；参数是资产ID；同步移除列表和待发送引用。
     */
    async removeAsset(assetId: string) {
      if (this.deletingAssetId) {
        return;
      }
      this.deletingAssetId = assetId;
      this.errorMessage = '';
      try {
        await deleteAsset(assetId);
        this.assets = this.assets.filter((item) => item.assetId !== assetId);
        this.selectedAssets = this.selectedAssets.filter((item) => item.assetId !== assetId);
      } catch (error) {
        this.errorMessage = error instanceof Error ? error.message : '资产删除失败';
        throw error;
      } finally {
        this.deletingAssetId = '';
      }
    },
  },
});

/**
 * 验证上传文件；参数是文件列表；返回符合客户端基础限制的文件。
 */
function validateFiles(files: File[]) {
  if (files.length === 0) {
    throw new Error('请先选择文件');
  }
  if (files.length > MAX_FILES_PER_BATCH) {
    throw new Error(`单次最多上传 ${MAX_FILES_PER_BATCH} 个文件`);
  }
  const oversized = files.find((file) => file.size > MAX_FILE_SIZE);
  if (oversized) {
    throw new Error(`文件“${oversized.name}”超过 20 MiB`);
  }
  return files;
}

/**
 * 判断资产是否可发送；参数是资产；返回解析和资产状态是否可用。
 */
function isReadyAsset(asset: ArtifactAsset) {
  return asset.parseStatus === 'ready' && asset.status !== 'deleted' && !asset.messageId;
}

/**
 * 按资产ID合并列表；参数是两组资产；返回保留前组顺序的去重列表。
 */
function mergeAssets(primary: ArtifactAsset[], secondary: ArtifactAsset[]) {
  return [...primary, ...secondary].filter((asset, index, all) =>
    all.findIndex((item) => item.assetId === asset.assetId) === index);
}
