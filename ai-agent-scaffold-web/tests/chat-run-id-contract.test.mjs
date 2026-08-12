import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

const chatStoreSource = readFileSync(resolve(import.meta.dirname, '../src/stores/chat.ts'), 'utf8');
const agentApiSource = readFileSync(resolve(import.meta.dirname, '../src/api/agent.ts'), 'utf8');

test('runId 生成兼容 HTTP 非安全上下文', () => {
  assert.match(chatStoreSource, /function createRunId\(\)\s*{\s*return `run_\$\{createId\(\)\}`;/);
  assert.doesNotMatch(chatStoreSource, /function createRunId\(\)\s*{\s*return `run_\$\{crypto\.randomUUID\(\)\}`;/);
  assert.match(chatStoreSource, /Date\.now\(\)\.toString\(36\)/);
  assert.match(chatStoreSource, /Math\.random\(\)\.toString\(36\)\.slice\(2\)/);

  const fallbackRunId = `run_${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`;
  assert.match(fallbackRunId, /^[A-Za-z0-9_-]+$/);
  assert.ok(fallbackRunId.length <= 64);
});

test('SSE heartbeat 不会被当成模型正文', () => {
  assert.match(agentApiSource, /if \(eventName === 'heartbeat'\) \{\s*return;\s*\}/);
  const heartbeatBranch = agentApiSource.indexOf("if (eventName === 'heartbeat')");
  const chunkBranch = agentApiSource.indexOf('handlers.onChunk?.(data)');
  assert.ok(heartbeatBranch >= 0 && heartbeatBranch < chunkBranch);
});
