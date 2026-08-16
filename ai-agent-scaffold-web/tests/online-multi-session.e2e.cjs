const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const username = process.env.E2E_USER || 'e2e_agent_1786557368797';
  const password = process.env.E2E_PASSWORD || 'E2eAgent!2026';
  const errors = [];
  page.on('pageerror', (error) => errors.push(`pageerror:${error.message}`));
  page.on('response', (response) => {
    if (response.status() >= 500) errors.push(`http${response.status()}:${response.url()}`);
  });

  try {
    await page.goto('http://lcodeagent.lcode.top/auth/login', { waitUntil: 'networkidle' });
    await page.locator('#username').fill(username);
    await page.locator('#password').fill(password);
    await page.getByRole('button', { name: '进入工作台' }).click();
    await page.waitForURL('**/dashboard', { timeout: 30000 });
    await page.goto('http://lcodeagent.lcode.top/chat', { waitUntil: 'domcontentloaded' });
    const guide = page.getByRole('dialog', { name: '选择你的运行引擎' });
    await guide.waitFor({ timeout: 5000 });
    await guide.getByRole('button', { name: '进入工作台' }).click();
    await page.locator('.compact-field--wide select').first().selectOption('100003');

    await page.waitForFunction(() => document.querySelectorAll('.session-item').length >= 2, null, { timeout: 30000 });
    const candidateIds = await page.locator('.session-item').evaluateAll((items) => items
      .filter((item) => !(item.querySelector('.session-delete') instanceof HTMLButtonElement)
        || !item.querySelector('.session-delete').disabled)
      .map((item) => item.getAttribute('data-session-id')).filter(Boolean));
    if (candidateIds.length < 2) throw new Error(`缺少两个可用会话: ${candidateIds.join(',')}`);
    const [firstSessionId, secondSessionId] = candidateIds;

    await page.locator(`[data-session-id="${firstSessionId}"] .session-open`).click();
    await page.locator('.composer-input').fill('请分析 Java 并发安全的三种实现思路，比较优缺点后给出结论。');
    const firstAccepted = page.waitForResponse((response) => response.url().includes('/api/v1/chat_stream')
      && response.request().method() === 'POST' && response.status() === 200, { timeout: 30000 });
    await page.locator('.composer-actions .button--primary').click();
    const firstResponse = await firstAccepted;
    await page.getByRole('button', { name: '取消' }).waitFor({ state: 'visible', timeout: 30000 });

    // 第一个 SSE 仍在运行时切换会话，第二个会话必须能独立发送。
    await page.locator(`[data-session-id="${secondSessionId}"] .session-open`).click();
    await page.waitForFunction((sessionId) => document.querySelector('.session-item--active')?.getAttribute('data-session-id') === sessionId,
      secondSessionId, { timeout: 30000 });
    await page.getByRole('button', { name: '发送' }).waitFor({ state: 'visible', timeout: 30000 });
    await page.locator('.composer-input').fill('请用三句话说明 Redis ZSet 的适用场景。');
    const secondAccepted = page.waitForResponse((response) => response.url().includes('/api/v1/chat_stream')
      && response.request().method() === 'POST' && response.status() === 200, { timeout: 30000 });
    await page.locator('.composer-actions .button--primary').click();
    const secondResponse = await secondAccepted;

    const firstPayload = firstResponse.request().postDataJSON();
    const secondPayload = secondResponse.request().postDataJSON();
    if (firstPayload.sessionId !== firstSessionId || secondPayload.sessionId !== secondSessionId
        || firstPayload.requestedRunId === secondPayload.requestedRunId) {
      throw new Error(`跨会话运行隔离失败: ${JSON.stringify({ firstPayload, secondPayload })}`);
    }
    if (errors.length) throw new Error(`online errors: ${errors.join(', ')}`);

    // 验收完成后显式取消两条测试运行，避免浏览器携带活跃 SSE 退出。
    if (await page.getByRole('button', { name: '取消' }).isVisible()) {
      await page.getByRole('button', { name: '取消' }).click();
      await page.getByRole('button', { name: '发送' }).waitFor({ state: 'visible', timeout: 30000 });
    }
    await page.locator(`[data-session-id="${firstSessionId}"] .session-open`).click();
    if (await page.getByRole('button', { name: '取消' }).isVisible()) {
      await page.getByRole('button', { name: '取消' }).click();
      await page.getByRole('button', { name: '发送' }).waitFor({ state: 'visible', timeout: 30000 });
    }
    console.log(JSON.stringify({ ok: true, username, firstSessionId, secondSessionId, errors }, null, 2));
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
