const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
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
    await page.getByRole('heading', { name: 'Agent 编排工作台' }).waitFor();
    await page.locator('.compact-field--wide select').first().selectOption('100003');
    await page.waitForTimeout(800);

    let childCount = 0;
    const sessionCount = await page.locator('.session-item').count();
    for (let index = 0; index < sessionCount && childCount < 2; index += 1) {
      const candidate = page.locator('.session-item').nth(index);
      await candidate.locator('.session-open').click();
      const expand = candidate.locator('.session-expand');
      if (await expand.getAttribute('aria-expanded') === 'false') await expand.click();
      await page.waitForTimeout(500);
      childCount = await candidate.locator('.session-children button').count();
    }
    if (childCount < 2) throw new Error('移动端未找到可打开的真实子 Agent 任务');
    await page.locator('.session-item--active .session-children button').nth(1).click();
    await page.locator('.task-detail').waitFor({ state: 'visible', timeout: 30000 });
    await page.getByRole('button', { name: '复制子任务结果' }).waitFor();

    const geometry = await page.evaluate(() => ({
      viewport: window.innerWidth,
      documentWidth: document.documentElement.scrollWidth,
      bodyWidth: document.body.scrollWidth,
    }));
    if (geometry.documentWidth > geometry.viewport + 2 || geometry.bodyWidth > geometry.viewport + 2) {
      throw new Error(`mobile horizontal overflow: ${JSON.stringify(geometry)}`);
    }
    if (errors.length) throw new Error(`online errors: ${errors.join(', ')}`);
    await page.screenshot({ path: '/tmp/lcodeagent-mobile.png', fullPage: true });
    console.log(JSON.stringify({ ok: true, username, geometry, childCount, errors }, null, 2));
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
