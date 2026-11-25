// Opens a finished project's index.html in Chromium: every console error and page error, and, when play-test scripts are given
// (dumped from the app by GamePlayScriptDump), each play test's result. Prints JSON.
// Usage: node browser_check.js <project dir> [scripts dir]
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const [dir, scripts] = process.argv.slice(2);
(async () => {
  const out = { errors: [], play: [] };
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 420, height: 900 } });
  page.on('console', m => { if (m.type() === 'error') out.errors.push('console: ' + m.text()); });
  page.on('pageerror', e => out.errors.push('page error: ' + e.message));
  page.on('requestfailed', r => out.errors.push('failed to load: ' + r.url().replace('file://' + dir, '')));
  await page.goto('file://' + path.join(dir, 'index.html'));
  await page.waitForTimeout(800);
  await page.close();
  if (scripts && fs.existsSync(scripts)) {
    for (const t of fs.readdirSync(scripts).sort()) {
      const file = path.join(scripts, t);
      const name = fs.readFileSync(file, 'utf8').split('\n')[0].slice(3);
      const p = await browser.newPage({ viewport: { width: 420, height: 900 } });
      const errs = [];
      p.on('pageerror', e => errs.push(e.message));
      await p.goto('file://' + path.join(dir, 'index.html'));
      await p.evaluate(fs.readFileSync(file, 'utf8')).catch(e => errs.push('script: ' + e.message));
      await p.waitForFunction('window.__pt && window.__pt.done', null, { timeout: 30000 }).catch(() => {});
      const r = await p.evaluate('window.__pt ? window.__pt.results : []').catch(() => []);
      out.play.push({ name, ok: r.length > 0 && r.every(x => x.ok), failed: r.filter(x => !x.ok).map(x => x.s + ' → ' + x.v), errors: errs });
      await p.close();
    }
  }
  await browser.close();
  console.log(JSON.stringify(out));
})();
