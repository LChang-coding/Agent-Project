import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const assetView = readFileSync(new URL('../src/views/assets/AssetCenterView.vue', import.meta.url), 'utf8');
const chatView = readFileSync(new URL('../src/views/chat/ChatWorkspaceView.vue', import.meta.url), 'utf8');

test('附件资产行使用可读文件元数据和紧凑来源标识', () => {
  assert.match(assetView, /formatFileType\(asset\.mimeType, asset\.fileName\)/);
  assert.match(assetView, /class="source-chip"/);
  assert.match(assetView, /class="asset-metadata"/);
  assert.match(assetView, /:title="asset\.fileName"/);
});

test('聊天附件抽屉有独立可视高度，不被输入框从行中截断', () => {
  assert.match(chatView, /insight-panel--attachments/);
  assert.match(chatView, /insight-body--attachments/);
  assert.match(chatView, /\.composer:has\(\.insight-panel\)/);
  assert.match(chatView, /scroll-snap-align:\s*start/);
});
