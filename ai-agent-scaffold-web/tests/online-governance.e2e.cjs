const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const username = `e2e_governance_${Date.now()}`;
  const password = 'E2eAgent!2026';
  const errors = [];
  page.on('pageerror', (error) => errors.push(`pageerror:${error.message}`));
  page.on('response', (response) => { if (response.status() >= 500) errors.push(`http${response.status()}:${response.url()}`); });
  try {
    await page.goto('http://lcodeagent.lcode.top/auth/register', { waitUntil: 'networkidle' });
    await page.locator('#tenantName').fill(`E2E Governance ${username}`);
    await page.locator('#username').fill(username);
    await page.locator('#nickname').fill('Governance Operator');
    await page.locator('#email').fill(`${username}@example.com`);
    await page.locator('#phone').fill(`1${String(Date.now()).slice(-10)}`);
    await page.locator('#password').fill(password);
    await page.getByRole('button', { name: '创建并登录' }).click();
    await page.waitForURL('**/dashboard', { timeout: 30000 });

    await page.goto('http://lcodeagent.lcode.top/agents', { waitUntil: 'domcontentloaded' });
    const supervisor = page.locator('.agent-row').filter({ hasText: '主 Agent' }).first();
    await supervisor.locator('.tool-permissions summary').click();
    const cards = supervisor.locator('.tool-permission');
    if (await cards.count() < 7) throw new Error(`工具权限清单不完整: ${await cards.count()}`);
    const searchPolicy = cards.filter({ hasText: '检索子 Agent 目录' });
    await searchPolicy.locator('select').first().selectOption('REQUIRE_APPROVAL');
    await searchPolicy.getByRole('button', { name: '保存该工具策略' }).click();
    await page.waitForTimeout(700);

    await page.goto('http://lcodeagent.lcode.top/chat', { waitUntil: 'domcontentloaded' });
    await page.locator('.compact-field--wide select').first().selectOption('100003');
    await page.locator('.composer-input').fill(`只调用一次 search_agent_catalog 检索 coding 模板，返回工具原始结果，不创建子 Agent。验收标识：${username}`);
    await page.locator('.composer-actions .button--primary').click();
    await page.locator('.approval-dialog').waitFor({ state: 'visible', timeout: 120000 });
    const approval = await page.locator('.approval-dialog').innerText();
    if (!approval.includes('允许 Agent 调用该工具？') || !approval.includes('search_agent_catalog')) throw new Error(`通用审批弹窗错误: ${approval}`);
    await page.getByRole('button', { name: /批准 1 项操作/ }).click();
    await page.waitForFunction(() => !document.querySelector('.composer-input')?.hasAttribute('disabled'), null, { timeout: 120000 });

    await page.getByRole('button', { name: '管理' }).click();
    await page.locator('.session-select').first().check();
    page.once('dialog', (dialog) => dialog.accept());
    await page.locator('.session-batch-bar').getByRole('button', { name: /删除 1/ }).click();
    await page.waitForFunction(() => document.querySelectorAll('.session-item').length === 0, null, { timeout: 30000 });

    await page.goto('http://lcodeagent.lcode.top/mcp', { waitUntil: 'domcontentloaded' });
    const mcpName = `E2E batch ${username}`;
    await page.locator('input[placeholder="例如：订单查询 MCP"]').fill(mcpName);
    await page.locator('textarea[placeholder="这个 MCP 提供哪些工具能力"]').fill('批量删除闭环测试资源');
    await page.locator('input[placeholder="https://example.com/mcp"]').fill('http://127.0.0.1:1/mcp');
    await page.getByRole('button', { name: '创建 MCP 草稿' }).click();
    const mcpRow = page.locator('tbody tr').filter({ hasText: mcpName });
    await mcpRow.waitFor({ state: 'visible', timeout: 30000 });
    await mcpRow.locator('input[type=checkbox]').check();
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: /批量删除 \(1\)/ }).click();
    await mcpRow.getByText('已禁用').waitFor({ state: 'visible', timeout: 30000 });

    await page.goto('http://lcodeagent.lcode.top/agents', { waitUntil: 'domcontentloaded' });
    if (await page.locator('.approval-dialog').isVisible()) await page.locator('.approval-close').click();
    const reviewAgent = page.locator('.agent-row').filter({ hasText: '通用审查 Agent' });
    await reviewAgent.locator('.batch-checkbox').check();
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: /批量删除 \(1\)/ }).click();
    await reviewAgent.getByText('已禁用', { exact: true }).waitFor({ state: 'visible', timeout: 30000 });

    if (errors.length) throw new Error(`浏览器发现服务端/页面错误: ${errors.join(', ')}`);
    console.log(JSON.stringify({ ok: true, username, genericApproval: true, sessionBatchDelete: true, mcpBatchDelete: true, agentBatchDelete: true, errors }, null, 2));
  } finally { await browser.close(); }
})().catch((error) => { console.error(error); process.exit(1); });
