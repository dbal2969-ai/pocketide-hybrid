// Plays every game task's play tests with the app's own script (dumped by GamePlayScriptDump) in Chromium, on the starting project
// and on the project with its reference fix. A fair task fails before and passes after.
// Usage: node bench/hybrid/check_games.js bench/hybrid/games <dumped scripts folder>
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path'), os = require('os');
const [root, scripts] = process.argv.slice(2);
function copy(src, dst) { for (const f of fs.readdirSync(src, { withFileTypes: true })) { const s = path.join(src, f.name), d = path.join(dst, f.name); if (f.isDirectory()) { fs.mkdirSync(d, { recursive: true }); copy(s, d); } else fs.copyFileSync(s, d); } }
async function play(browser, dir, file) {
  const page = await browser.newPage({ viewport: { width: 420, height: 900 } });
  await page.goto('file://' + path.join(dir, 'index.html'));
  await page.evaluate(fs.readFileSync(file, 'utf8'));
  await page.waitForFunction('window.__pt && window.__pt.done', null, { timeout: 30000 }).catch(() => {});
  const r = await page.evaluate('window.__pt ? window.__pt.results : [{s:"never finished",ok:false}]');
  await page.close();
  return r;
}
(async () => {
  const browser = await chromium.launch();
  let unfair = 0;
  for (const task of fs.readdirSync(path.join(root, 'tasks')).sort()) {
    const res = {};
    for (const phase of ['before', 'after']) {
      const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'game-'));
      copy(path.join(root, 'tasks', task), dir);
      if (phase === 'after') copy(path.join(root, 'solutions', task), dir);
      const tests = fs.readdirSync(path.join(scripts, task)).sort();
      res[phase] = [];
      for (const t of tests) {
        const r = await play(browser, dir, path.join(scripts, task, t));
        const name = fs.readFileSync(path.join(scripts, task, t), 'utf8').split('\n')[0].slice(3);
        res[phase].push({ name, ok: r.length > 0 && r.every(x => x.ok), bad: r.filter(x => !x.ok).map(x => x.s + ' → ' + x.v) });
      }
    }
    const fairBefore = res.before.some(t => !t.ok), fairAfter = res.after.every(t => t.ok);
    if (!fairBefore || !fairAfter) unfair++;
    console.log(`${task}: ${fairBefore && fairAfter ? 'fair' : 'UNFAIR'} · before: ${res.before.filter(t => t.ok).length}/${res.before.length} pass · after: ${res.after.filter(t => t.ok).length}/${res.after.length} pass`);
    for (const t of res.after.filter(t => !t.ok)) console.log('   after, failing: ' + t.name + ': ' + t.bad.join('; '));
  }
  await browser.close();
  process.exit(unfair ? 1 : 0);
})();
