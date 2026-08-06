import assert from 'node:assert/strict';
import test from 'node:test';

import { toSessionRagSettingRequest } from '../src/api/session-rag-contract.ts';

test('RAG 调用方式使用后端 invocationMode 字段', () => {
  const payload = toSessionRagSettingRequest({
    mode: 'MANUAL',
    invocationMode: 'AGENT_TOOL',
    selectedBindingIds: ['binding-1'],
    expectedRevision: 4,
  });

  assert.equal(payload.invocationMode, 'AGENT_TOOL');
  assert.equal('ragInvocationMode' in payload, false);
  assert.equal(payload.enabled, true);
});
