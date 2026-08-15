import assert from 'node:assert/strict';
import test from 'node:test';

import {
  approvalPresentation,
  executeBatchOperation,
  summarizeApprovalInput,
} from '@/domain/tool-governance';

test('batch operation keeps failed ids selected and reports partial success', async () => {
  const result = await executeBatchOperation(['mcp-1', 'mcp-2', 'mcp-3'], async (id) => {
    if (id === 'mcp-2') throw new Error('forbidden');
  });

  assert.deepEqual(result.succeededIds, ['mcp-1', 'mcp-3']);
  assert.deepEqual(result.failedIds, ['mcp-2']);
  assert.equal(result.message, '2 项已删除，1 项失败；已保留失败项便于重试');
});

test('approval presentation explains tool scope and risk consistently', () => {
  assert.deepEqual(approvalPresentation('create_subagent_instances'), {
    category: '子 Agent 编排',
    risk: '资源消耗',
    tone: 'warning',
    title: '批准创建子 Agent 任务？',
    description: '将创建独立子会话并消耗模型额度，全部完成后由主 Agent 统一汇总。',
  });
  assert.equal(approvalPresentation('mcp:mcp-time').category, 'MCP 外部工具');
  assert.equal(approvalPresentation('skill:review').risk, '指令加载');
  assert.equal(approvalPresentation('select_workflow_route').risk, '流程变更');
});

test('approval input summary masks secrets and keeps useful parameter context', () => {
  const summary = summarizeApprovalInput({
    query: '检索父子分块',
    apiKey: 'secret-value',
    nested: { token: 'nested-secret', limit: 5 },
  });

  assert.deepEqual(summary, [
    { key: 'query', value: '检索父子分块', sensitive: false },
    { key: 'apiKey', value: '已隐藏', sensitive: true },
    { key: 'nested', value: '{"token":"已隐藏","limit":5}', sensitive: true },
  ]);
});
