const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const username = process.env.E2E_USER || `e2e_agent_${Date.now()}`;
  const runMarker = Date.now();
  const password = 'E2eAgent!2026';
  const errors = [];
  page.on('pageerror', (error) => errors.push(`pageerror:${error.message}`));
  page.on('response', (response) => {
    if (response.status() >= 500) errors.push(`http${response.status()}:${response.url()}`);
  });
  try {
    if (process.env.E2E_USER) {
      await page.goto('http://lcodeagent.lcode.top/auth/login', { waitUntil: 'networkidle' });
      await page.locator('#username').fill(username); await page.locator('#password').fill(password);
      await page.getByRole('button', { name: '进入工作台' }).click();
    } else {
      await page.goto('http://lcodeagent.lcode.top/auth/register', { waitUntil: 'networkidle' });
      await page.locator('#tenantName').fill(`E2E Multi Agent ${username}`);
      await page.locator('#username').fill(username);
      await page.locator('#nickname').fill('E2E Operator');
      await page.locator('#email').fill(`${username}@example.com`);
      await page.locator('#phone').fill(`1${String(Date.now()).slice(-10)}`);
      await page.locator('#password').fill(password);
      await page.getByRole('button', { name: '创建并登录' }).click();
    }
    try { await page.waitForURL('**/dashboard', { timeout: 30000 }); }
    catch (error) { throw new Error(`注册登录未跳转 url=${page.url()} body=${(await page.locator('body').innerText()).slice(-1200)}`); }

    await page.goto('http://lcodeagent.lcode.top/agents', { waitUntil: 'domcontentloaded' });
    const supervisor = page.locator('.agent-row').filter({ hasText: '主 Agent' }).first();
    await supervisor.waitFor({ state: 'visible' });
    await supervisor.locator('.tool-permissions summary').click();
    const permissionCards = supervisor.locator('.tool-permission');
    if (await permissionCards.count() < 7) throw new Error(`Supervisor 工具权限数量不足: ${await permissionCards.count()}`);
    const createPermission = permissionCards.filter({ hasText: '创建子 Agent' });
    await createPermission.locator('select').first().selectOption('REQUIRE_APPROVAL');
    await createPermission.locator('input[type=number]').fill('600');
    await createPermission.getByRole('button', { name: '保存该工具策略' }).click();
    await page.waitForTimeout(800);

    await page.goto('http://lcodeagent.lcode.top/chat', { waitUntil: 'domcontentloaded' });
    await page.locator('.compact-field--wide select').first().selectOption('100003');
    await page.locator('.composer-input').fill(`这是一次 Multi-Agent UI 闭环验收。必须先调用 search_agent_catalog，然后 create_subagent_instances 必须且只能调用一次；该次调用的 tasks 数组必须恰好包含两个元素，不得拆分或遗漏：第一个 agentTemplateId=100001，实现线程安全 Java 令牌桶并审查；第二个 agentTemplateId=100002，调研可再生能源、电动车和碳捕获近期进展。收到两个异步回调后再统一汇总，禁止主 Agent 自行完成。验收标识：${username}`);
    await page.locator('.composer-actions .button--primary').click();

    await page.locator('.approval-dialog').waitFor({ state: 'visible', timeout: 120000 });
    const approvalText = await page.locator('.approval-dialog').innerText();
    if (!approvalText.includes('批准创建子 Agent 任务？') || !approvalText.includes('通用编码 Agent') || !approvalText.includes('通用调查 Agent')) {
      throw new Error(`审批弹窗内容不完整: ${approvalText.slice(0, 1000)}`);
    }
    await page.screenshot({ path: '/tmp/lcodeagent-approval.png', fullPage: true });
    await page.getByRole('button', { name: /批准 2 项操作/ }).click();

    await page.locator('.run-card').waitFor({ state: 'visible', timeout: 30000 });
    const sessionExpand = page.locator('.session-expand').first();
    if (await sessionExpand.getAttribute('aria-expanded') === 'false') await sessionExpand.click();
    await page.waitForFunction(() => document.querySelectorAll('.session-children button').length >= 3, null, { timeout: 60000 });
    await page.screenshot({ path: '/tmp/lcodeagent-running.png', fullPage: true });

    const childButtons = page.locator('.session-children button');
    await childButtons.nth(1).click();
    await page.locator('.task-detail').waitFor({ state: 'visible' });
    await page.screenshot({ path: '/tmp/lcodeagent-child-detail.png', fullPage: true });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1000);
    const restoredExpand = page.locator('.session-expand').first();
    if (await restoredExpand.getAttribute('aria-expanded') === 'false') await restoredExpand.click();
    await page.waitForFunction(() => document.querySelectorAll('.session-children button').length >= 3, null, { timeout: 60000 });
    const restoredChildren = await page.locator('.session-children button').count();
    if (restoredChildren < 3) throw new Error(`刷新后子 Agent 树未恢复: ${restoredChildren}`);

    await page.waitForFunction(() => {
      const button = document.querySelector('.session-item--active .session-delete');
      return button instanceof HTMLButtonElement && !button.disabled;
    }, null, { timeout: 240000 });
    if (!process.env.E2E_KEEP_SESSION) {
      const activeSession = page.locator('.session-item--active');
      const activeSessionId = await activeSession.getAttribute('data-session-id');
      if (!activeSessionId) throw new Error('当前会话缺少稳定识别属性');
      await page.getByRole('button', { name: '管理' }).click();
      await activeSession.locator('.session-select').check();
      page.once('dialog', (dialog) => dialog.accept());
      await page.locator('.session-batch-bar').getByRole('button', { name: /删除 1/ }).click();
      await page.waitForFunction((sessionId) => !document.querySelector(`[data-session-id="${sessionId}"]`),
        activeSessionId, { timeout: 30000 });
    }

    await page.goto('http://lcodeagent.lcode.top/mcp', { waitUntil: 'domcontentloaded' });
    const mcpName = `E2E batch ${username} ${runMarker}`;
    await page.locator('input[placeholder="例如：订单查询 MCP"]').fill(mcpName);
    await page.locator('textarea[placeholder="这个 MCP 提供哪些工具能力"]').fill('批量删除闭环测试资源');
    await page.locator('input[placeholder="https://example.com/mcp"]').fill('http://127.0.0.1:1/mcp');
    await page.getByRole('button', { name: '创建 MCP 草稿' }).click();
    const createdMcpRow = page.locator('tbody tr').filter({ hasText: mcpName });
    await createdMcpRow.waitFor({ state: 'visible', timeout: 30000 });
    await createdMcpRow.locator('input[type=checkbox]').check();
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: /批量删除 \(1\)/ }).click();
    await page.waitForFunction(() => document.body.innerText.includes('已禁用'), null, { timeout: 30000 });

    console.log(JSON.stringify({ ok: true, username, approval: true, childCount: restoredChildren, batchDelete: true, errors }, null, 2));
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
