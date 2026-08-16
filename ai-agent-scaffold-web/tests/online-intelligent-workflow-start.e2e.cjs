const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [];
  const postRequests = [];
  page.on('pageerror', (error) => errors.push(`pageerror:${error.message}`));
  page.on('response', (response) => {
    if (response.status() >= 500) errors.push(`http${response.status()}:${response.url()}`);
  });
  page.on('request', (request) => {
    if (request.method() === 'POST') postRequests.push(request.url());
  });
  page.on('dialog', (dialog) => dialog.accept());
  const workflowName = `E2E 智能工作流 ${Date.now()}`;
  const username = process.env.E2E_USER;
  const password = process.env.E2E_PASSWORD;
  if (!username || !password) throw new Error('请通过 E2E_USER 和 E2E_PASSWORD 提供线上验收账号');
  let createdWorkflowId = '';
  try {
    await page.goto('http://lcodeagent.lcode.top/auth/login', { waitUntil: 'networkidle' });
    await page.locator('#username').fill(username);
    await page.locator('#password').fill(password);
    await page.getByRole('button', { name: '进入工作台' }).click();
    await page.waitForURL('**/dashboard', { timeout: 30000 });
    await page.evaluate(() => localStorage.setItem('ai-agent-scaffold-mode-guide-seen-v1', '1'));
    await page.goto('http://lcodeagent.lcode.top/workflow', { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '工作流编排' }).waitFor();
    await page.locator('.create-box input[placeholder="工作流名称"]').fill(workflowName);
    const created = page.waitForResponse((response) => response.request().method() === 'POST'
      && /\/api\/v1\/workflows$/.test(response.url()), { timeout: 30000 });
    await page.getByRole('button', { name: '创建并刷新' }).click();
    const createPayload = await (await created).json();
    createdWorkflowId = createPayload?.data?.workflowId || '';
    const createdItem = page.locator('.workflow-item').filter({ hasText: workflowName });
    await createdItem.waitFor({ timeout: 30000 });
    await page.locator('.template-library__controls select').nth(1).selectOption('INTELLIGENT');
    await page.locator('.template-library__picker select').selectOption('prod-intelligent-customer-router');
    await page.getByRole('button', { name: '载入当前草稿' }).click();
    if (!await page.locator('.workflow-kind-tabs button').nth(1).evaluate((button) => button.classList.contains('active'))) {
      throw new Error('智能工作流模板未成功载入');
    }
    const published = page.waitForResponse((response) => response.request().method() === 'POST'
      && response.url().includes('/publish'), { timeout: 30000 });
    await page.getByRole('button', { name: '发布运行' }).click();
    const publishResponse = await published;
    if (publishResponse.status() !== 200) throw new Error(`测试工作流发布失败: ${publishResponse.status()}`);
    const publishPayload = await publishResponse.json();
    const publishedKind = publishPayload?.data?.graph?.workflowKind || null;

    const targetsLoaded = page.waitForResponse((response) => response.request().method() === 'GET'
      && /\/api\/v1\/workflows(?:\?.*)?$/.test(response.url()), { timeout: 30000 });
    await page.goto('http://lcodeagent.lcode.top/chat', { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: 'Agent 编排工作台' }).waitFor();
    await targetsLoaded;

    const agentMode = page.locator('[data-source-mode="agent"]');
    const workflowMode = page.locator('[data-source-mode="workflow"]');
    if (await workflowMode.getAttribute('aria-checked') === 'true') {
      await agentMode.click();
    }
    const defaultDetailLoaded = page.waitForResponse((response) => response.request().method() === 'GET'
      && /\/api\/v1\/workflows\/workflow_[^/?]+(?:\?.*)?$/.test(response.url()), { timeout: 30000 });
    await workflowMode.click();
    await defaultDetailLoaded;
    const workflowSelect = page.locator('.compact-field--wide select').first();
    await page.waitForFunction((name) => {
      const mode = document.querySelector('[data-source-mode="workflow"]');
      const select = document.querySelector('.compact-field--wide select');
      return mode instanceof HTMLButtonElement && mode.getAttribute('aria-checked') === 'true'
        && select instanceof HTMLSelectElement
        && Array.from(select.options).some((option) => option.textContent?.includes(name));
    }, workflowName);
    const options = await workflowSelect.locator('option').evaluateAll((items) =>
      items.map((item) => ({ value: item.value, text: item.textContent || '' })));
    const target = options.find((option) => option.text.includes(workflowName));
    if (!target?.value) throw new Error(`没有可验收的工作流: ${JSON.stringify(options)}`);
    if (await workflowSelect.inputValue() !== target.value) {
      const targetDetailLoaded = page.waitForResponse((response) => response.request().method() === 'GET'
        && response.url().includes(`/api/v1/workflows/${target.value}`), { timeout: 30000 });
      await workflowSelect.selectOption(target.value);
      await targetDetailLoaded;
    }
    await page.waitForTimeout(250);
    const detailKind = await page.evaluate(async (workflowId) => {
      const token = localStorage.getItem('ai_agent_scaffold_access_token');
      const result = await fetch(`/api/v1/workflows/${workflowId}`, { headers: { Authorization: `Bearer ${token}` } });
      return (await result.json())?.data?.graph?.workflowKind;
    }, target.value);
    await page.locator('.composer-input').fill(`工作流事务验收 ${Date.now()}：仅回复“红色”。`);

    const startedAt = Date.now();
    const accepted = page.waitForResponse((response) => response.request().method() === 'POST'
      && (response.url().includes('/api/v1/intelligent-workflow-runs')
        || response.url().includes('/api/v1/workflow-runs')), { timeout: 30000 });
    await page.locator('.composer-actions .button--primary').click();
    let response;
    try {
      response = await accepted;
    } catch (error) {
      const diagnostics = await page.evaluate(() => ({
        sendDisabled: document.querySelector('.composer-actions .button--primary')?.disabled,
        operation: document.querySelector('.operation-state')?.textContent,
        body: document.body.innerText.slice(-1600),
      }));
      throw new Error(`未观察到工作流启动请求 posts=${JSON.stringify(postRequests)} diagnostics=${JSON.stringify(diagnostics)}`, { cause: error });
    }
    const elapsedMs = Date.now() - startedAt;
    if (!response.url().includes('/api/v1/intelligent-workflow-runs') || response.status() !== 200 || elapsedMs >= 10000) {
      throw new Error(`智能工作流启动异常 url=${response.url()} status=${response.status()} elapsedMs=${elapsedMs} publishedKind=${publishedKind} detailKind=${detailKind}`);
    }
    await page.locator('.execution-panel').last().waitFor({ state: 'visible', timeout: 30000 });
    if (errors.length) throw new Error(`online errors: ${errors.join(', ')}`);

    const cancel = page.getByRole('button', { name: '取消' });
    if (await cancel.isVisible()) await cancel.click();
    await page.goto('http://lcodeagent.lcode.top/workflow', { waitUntil: 'domcontentloaded' });
    const createdWorkflow = page.locator('.workflow-item').filter({ hasText: workflowName });
    await createdWorkflow.click();
    await page.getByRole('button', { name: '删除工作流' }).click();
    await createdWorkflow.waitFor({ state: 'detached', timeout: 30000 });
    console.log(JSON.stringify({ ok: true, workflow: target.text, elapsedMs, cleaned: true, errors }, null, 2));
  } finally {
    if (createdWorkflowId && !page.isClosed()) {
      try {
        const token = await page.evaluate(() => localStorage.getItem('ai_agent_scaffold_access_token'));
        await page.request.delete(`http://lcodeagent.lcode.top/api/v1/workflows/${createdWorkflowId}`, {
          headers: token ? { Authorization: `Bearer ${token}` } : {},
        });
      } catch {
        // 主断言优先，清理失败由测试输出和后端审计定位。
      }
    }
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
