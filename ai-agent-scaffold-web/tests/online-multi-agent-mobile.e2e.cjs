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

    const multiAgentSession = page.locator('.session-item').filter({ hasText: 'Multi' }).first();
    if (await multiAgentSession.count()) {
      await multiAgentSession.locator('.session-open').click();
      const expand = multiAgentSession.locator('.session-expand');
      if (await expand.count() && await expand.getAttribute('aria-expanded') === 'false') await expand.click();
      await page.waitForTimeout(800);
    }

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
    console.log(JSON.stringify({ ok: true, username, geometry, childCount: await page.locator('.session-children button').count(), errors }, null, 2));
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
