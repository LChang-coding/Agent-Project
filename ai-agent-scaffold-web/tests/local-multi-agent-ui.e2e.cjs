const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const baseUrl = process.env.E2E_BASE_URL || 'http://127.0.0.1:4177';
const now = '2026-08-16T04:45:00Z';
const snapshot = {
  sessionId: 'session-multi-1',
  version: 'v3',
  active: true,
  inputLocked: true,
  phase: 'EXECUTING',
  currentRunId: 'run-parent-1',
  runs: [{
    parentRunId: 'run-parent-1',
    parentAgentId: '100003',
    phase: 'EXECUTING',
    createdAt: now,
    tasks: [
      {
        taskId: 'task-code-001', childAgentId: '100001', childSessionId: 'child-1',
        instruction: '实现线程安全的 Java 令牌桶并完成审查', traceId: 'trace-code-1234567890abcdef',
        status: 'SUCCEEDED', callbackStatus: 'DELIVERED', attempt: 1,
        resultSummary: '已完成令牌桶实现与并发安全审查。', createdAt: now, completedAt: now,
      },
      {
        taskId: 'task-research-002', childAgentId: '100002', childSessionId: 'child-2',
        instruction: '调研可再生能源、电动车与碳捕获进展', traceId: 'trace-research-1234567890abcd',
        status: 'RUNNING', callbackStatus: 'PENDING', attempt: 1, createdAt: now,
      },
    ],
  }],
  approvals: [],
};

function ok(data) {
  return { code: '0000', info: '成功', traceId: 'trace-operation', data };
}

async function installMocks(context) {
  await context.addInitScript(() => {
    localStorage.setItem('ai_agent_scaffold_access_token', 'test-token');
    localStorage.setItem('ai_agent_scaffold_auth_meta', JSON.stringify({
      tokenType: 'Bearer', expiresIn: 3600, tenantId: 'tenant-1', userId: 'user-1',
      username: 'qa-user', roleCode: 'owner',
    }));
    localStorage.setItem('ai_agent_scaffold_last_session_id', 'session-multi-1');
  });
  await context.route('**/api/**', async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname.startsWith('/api') ? url.pathname.slice(4) : url.pathname;
    if (path.endsWith('/orchestration/stream')) {
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: `event:orchestration_snapshot\ndata:${JSON.stringify(snapshot)}\n\n`,
      });
    }
    if (path === '/v1/tool-approvals/stream') {
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
    }
    if (path === '/v1/auth/me') {
      return route.fulfill({ json: ok({
        tenantId: 'tenant-1', userId: 'user-1', username: 'qa-user', nickname: 'QA User', roleCode: 'owner',
      }) });
    }
    if (path === '/v1/query_ai_agent_config_list') {
      return route.fulfill({ json: ok([
        { agentId: '100003', agentName: '通用编排 Agent', description: 'Supervisor', orchestrationRole: 'SUPERVISOR' },
        { agentId: '100001', agentName: '通用编码 Agent', description: 'Coding', orchestrationRole: 'WORKER' },
        { agentId: '100002', agentName: '通用调查 Agent', description: 'Research', orchestrationRole: 'WORKER' },
      ]) });
    }
    if (path === '/v1/workflows') return route.fulfill({ json: ok([]) });
    if (path === '/v1/workflows/node-options') {
      return route.fulfill({ json: ok({
        agents: [], models: [{ value: 'deepseek-chat', label: 'DeepSeek Chat' }],
        mcpTools: [], skills: [], platformTools: [],
      }) });
    }
    if (path === '/v1/sessions') {
      return route.fulfill({ json: ok({
        items: [{
          sessionId: 'session-multi-1', agentId: '100003', agentName: '通用编排 Agent', sourceType: 'agent',
          title: 'Multi-Agent 闭环验收', status: 'active', lastMessageTime: now, contextRevision: 3,
          ragEnabled: false, ragMode: 'OFF', ragInvocationMode: 'AUTO_CONTEXT',
        }],
        hasMore: false,
      }) });
    }
    if (path === '/v1/sessions/session-multi-1/messages') {
      return route.fulfill({ json: ok({
        sessionId: 'session-multi-1',
        items: [{
          messageId: 'msg-user', runId: 'run-parent-1', traceId: 'trace-parent-1234567890abcdef',
          role: 'user', contentType: 'text', content: '请并行完成编码与调研任务', sequenceNo: 1, createTime: now,
        }],
        hasMore: false,
      }) });
    }
    if (path === '/v1/sessions/session-multi-1/rag-setting') {
      return route.fulfill({ json: ok({
        sessionId: 'session-multi-1', enabled: false, bindingConfigured: false,
        targetType: 'AGENT', targetId: '100003', message: 'RAG已关闭。', mode: 'OFF',
        invocationMode: 'AUTO_CONTEXT', selectedBindingIds: [], eligibleBindings: [], revision: 1,
      }) });
    }
    if (path === '/v1/sessions/session-multi-1/orchestration') {
      return route.fulfill({ json: ok(snapshot) });
    }
    if (path.includes('/orchestration/tasks/')) {
      const taskId = path.split('/').at(-1);
      const task = snapshot.runs[0].tasks.find((item) => item.taskId === taskId);
      const detail = taskId === 'task-code-001'
        ? { ...task, fullContext: 'public final class TokenBucket { threadSafe(); }\n\n审查：时钟单调、锁内补充、溢出保护。' }
        : task;
      return route.fulfill({ json: ok(detail) });
    }
    if (path === '/v1/tools/catalog' || path === '/v1/tools/calls') return route.fulfill({ json: ok([]) });
    if (path.endsWith('/context-insight') || path.endsWith('/model-usage')) return route.fulfill({ json: ok(null) });
    if (path === '/v1/assets') return route.fulfill({ json: ok({ items: [], hasMore: false }) });
    return route.fulfill({ json: ok(null) });
  });
}

async function verifyDesktop(browser) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await installMocks(context);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(`${baseUrl}/chat`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: 'Agent 编排工作台' }).waitFor();
  const guide = page.getByRole('dialog', { name: '选择你的运行引擎' });
  await guide.waitFor();
  await guide.getByText('WAIT_ALL').waitFor();
  await guide.getByText('智能路由', { exact: true }).waitFor();
  await page.screenshot({ path: '/tmp/agent-mode-guide-desktop.png', fullPage: true });
  await guide.getByRole('button', { name: '进入工作台' }).click();
  await page.getByRole('link', { name: '总览', exact: true }).click();
  await page.getByRole('link', { name: 'Agent 编排', exact: true }).click();
  await guide.waitFor();
  await guide.getByRole('button', { name: '进入工作台' }).click();
  await page.locator('.run-card').waitFor();
  await page.waitForFunction(() => document.querySelectorAll('.session-children button').length === 3);
  assert.equal(await page.locator('.composer-actions button').last().isDisabled(), true, 'WAIT_ALL 应锁定发送');
  assert.equal(await page.getByRole('button', { name: '停止 1 个子任务' }).isEnabled(), true, '停止操作应保留');
  await page.getByRole('button', { name: '管理' }).click();
  assert.equal(await page.locator('.session-item--active .session-select').isDisabled(), true, '运行中会话不应可选');
  assert.match(await page.locator('.session-batch-note').innerText(), /运行中会话已排除/);
  await page.getByRole('button', { name: '完成' }).click();
  await page.locator('[data-subagent-task-id="task-code-001"][data-subagent-trigger="session-tree"]').click();
  await page.locator('.task-detail').waitFor();
  await page.getByRole('button', { name: '复制子任务结果' }).click();
  await page.getByRole('button', { name: '子任务结果已复制' }).waitFor();
  const geometry = await page.evaluate(() => ({
    viewport: innerWidth,
    documentWidth: document.documentElement.scrollWidth,
    bodyWidth: document.body.scrollWidth,
  }));
  assert.ok(geometry.documentWidth <= geometry.viewport + 2 && geometry.bodyWidth <= geometry.viewport + 2,
    `desktop horizontal overflow: ${JSON.stringify(geometry)}`);
  assert.deepEqual(errors, []);
  await page.screenshot({ path: '/tmp/agent-ui-desktop.png', fullPage: true });
  await context.close();
  return geometry;
}

async function verifyMobile(browser) {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  await installMocks(context);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(`${baseUrl}/chat`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: 'Agent 编排工作台' }).waitFor();
  const guide = page.getByRole('dialog', { name: '选择你的运行引擎' });
  await guide.waitFor();
  await page.screenshot({ path: '/tmp/agent-mode-guide-mobile.png', fullPage: true });
  await guide.getByRole('button', { name: '进入工作台' }).click();
  await page.locator('[data-subagent-task-id="task-code-001"][data-subagent-trigger="session-tree"]').click();
  await page.locator('.task-detail').waitFor();
  const geometry = await page.evaluate(() => ({
    viewport: innerWidth,
    documentWidth: document.documentElement.scrollWidth,
    bodyWidth: document.body.scrollWidth,
  }));
  assert.ok(geometry.documentWidth <= geometry.viewport + 2 && geometry.bodyWidth <= geometry.viewport + 2,
    `mobile horizontal overflow: ${JSON.stringify(geometry)}`);
  assert.deepEqual(errors, []);
  await page.screenshot({ path: '/tmp/agent-ui-mobile.png', fullPage: true });
  await context.close();
  return geometry;
}

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  });
  try {
    const desktop = await verifyDesktop(browser);
    const mobile = await verifyMobile(browser);
    console.log(JSON.stringify({ ok: true, desktop, mobile }, null, 2));
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
