// Plays an app built from scratch the way a person would, in Chromium at phone width, and prints JSON {checks:[{name, ok, detail}]}.
// The model chose every file and name, so nothing here relies on its structure: buttons are found by their text, inputs by type,
// results by what the page shows. Usage: node checks.js <project dir> <snake|calculator|todo|tictactoe|tip>
const { chromium } = require('playwright');
const path = require('path');
const [dir, kind] = process.argv.slice(2);

// records where listeners are added, before the page's own scripts run
const RECORD = `(() => {
  window.__ev = [];
  const add = EventTarget.prototype.addEventListener;
  EventTarget.prototype.addEventListener = function (type, f, o) {
    const where = this === window ? 'window' : this === document ? 'document' : this === document.body ? 'body' : (this.tagName || 'node').toLowerCase();
    window.__ev.push(type + '@' + where);
    return add.call(this, type, f, o);
  };
})();`;

const checks = [];
function check(name, ok, detail = '') { checks.push({ name, ok: !!ok, detail: String(detail).slice(0, 200) }); }

async function textOf(page) { return page.evaluate(() => document.body ? document.body.innerText : ''); }
async function shownText(page) {   // what the page shows outside its buttons (a calculator's display, a result line)
  return page.evaluate(() => {
    const parts = [];
    const walk = n => { for (const c of n.childNodes) { if (c.nodeType === 3) parts.push(c.textContent); else if (c.nodeType === 1 && !/^(BUTTON|SCRIPT|STYLE)$/.test(c.tagName)) { if (c.tagName === 'INPUT' || c.tagName === 'OUTPUT') parts.push(c.value || c.textContent); walk(c); } } };
    walk(document.body); return parts.join(' ').replace(/\s+/g, ' ');
  });
}
async function tapText(page, re) {
  return page.evaluate(src => {
    const r = new RegExp(src, 'i');
    const el = [...document.querySelectorAll('button, [role=button], a, input[type=button], input[type=submit], div, span')]
      .filter(e => e.offsetParent !== null || e === document.body)
      .find(e => r.test((e.value || e.textContent || e.getAttribute('aria-label') || '').trim()) && (e.tagName === 'BUTTON' || e.children.length === 0 || e.getAttribute('role') === 'button'));
    if (!el) return false;
    el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true })); el.click(); return true;
  }, re.source);
}
async function shot(page) { const b = await page.screenshot(); return require('crypto').createHash('md5').update(b).digest('hex'); }
const wait = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true });
  await ctx.addInitScript(RECORD);
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push('page error: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  page.on('requestfailed', r => errors.push('failed to load: ' + r.url().split('/').pop()));
  const url = 'file://' + path.join(dir, 'index.html');
  try { await page.goto(url); } catch (e) { check('the page opens', false, e.message); }
  await wait(700);
  check('loads with no errors', errors.length === 0, errors.join('; '));
  const width = await page.evaluate(() => document.documentElement.scrollWidth);
  check('fits a phone screen (no sideways scrolling at 390 px)', width <= 392, 'page is ' + width + ' px wide');
  check('shows something', (await textOf(page)).trim().length > 0 || await page.evaluate(() => !!document.querySelector('canvas')));
  const ev = await page.evaluate(() => window.__ev);

  try {
    if (kind === 'snake') {
      check('draws a board', await page.evaluate(() => !!document.querySelector('canvas') || document.querySelectorAll('div, td').length >= 100));
      check('the arrow keys reach the game without clicking first', ev.some(e => /^keydown@(window|document|body)$/.test(e)),
        'keydown listeners: ' + (ev.filter(e => e.startsWith('keydown')).join(', ') || 'none'));
      const onScreen = await page.evaluate(() => [...document.querySelectorAll('button, [role=button]')].some(b => /[▲▼◀▶↑↓←→⬆⬇⬅➡]|^\s*(up|down|left|right)\s*$/i.test(b.textContent)));
      check('can be steered by touch (swipes or on-screen buttons)', onScreen || ev.some(e => /^(touchstart|touchend|pointerdown|pointerup|swipe)@/.test(e)));
      check('shows a score', /score/i.test(await textOf(page)));
      if (!await tapText(page, /^(start|play|start game|play game|▶ start|tap to start)$/)) await page.keyboard.press('Space').catch(() => {});   // Space pauses some games
      const a = await shot(page); await wait(1500); const b = await shot(page);
      check('the game moves', a !== b);
      let over = false;
      for (let i = 0; i < 40 && !over; i++) { await wait(500); over = /game over|you lose|you died|crash|hit the wall|final score/i.test(await textOf(page)); }
      check('hitting a wall ends the game (left alone, it shows game over)', over);
      if (over) {
        const again = await tapText(page, /(play again|restart|new game|try again|^start$|^play$)/);
        if (!again) await page.keyboard.press('Space').catch(() => {});
        await wait(800);
        check('can play again after game over', again && !/game over|you lose/i.test(await textOf(page)) || (again && (await shot(page)) !== (await (async () => { await wait(800); return shot(page); })())));
      } else check('can play again after game over', false, 'never reached game over');
    }

    if (kind === 'calculator') {
      const sym = { '+': /^\+$/, '×': /^(×|\*|x|X)$/, '÷': /^(÷|\/)$/, '−': /^(−|-|–)$/, '=': /^=$/, 'C': /^(C|AC|CE|Clear)$/ };
      async function press(seq) { for (const k of seq) { const ok = await tapText(page, sym[k] || new RegExp('^' + k + '$')); if (!ok) return 'no button for ' + k; await wait(60); } return ''; }
      async function calc(seq, want, name) {
        await press(['C']);
        const miss = await press(seq); const shown = await shownText(page);
        const ok = !miss && new RegExp('(^|[^\\d.])' + want.replace('-', '[-−]') + '(\\.0+)?($|[^\\d.])').test(shown.trim());
        check(name, ok, miss || ('shows: ' + shown.trim().slice(-60)));
      }
      await calc(['7', '+', '8', '='], '15', '7 + 8 = 15');
      await calc(['6', '×', '7', '='], '42', '6 × 7 = 42');
      await calc(['9', '÷', '3', '='], '3', '9 ÷ 3 = 3');
      await calc(['2', '−', '5', '='], '-3', '2 − 5 = −3');
      await press(['9', '9']); await press(['C']);
      check('C clears', !/99/.test(await shownText(page)));
    }

    if (kind === 'todo') {
      async function add(t) {
        const input = await page.$('input[type=text], input:not([type]), textarea');
        if (!input) return false;
        await input.fill(t);
        const clicked = await tapText(page, /^(\+|add|add item|add task|➕)$/);
        if (!clicked) await input.press('Enter');
        await wait(300); return true;
      }
      const ok1 = await add('Buy milk'); const ok2 = await add('Walk dog');
      check('adds items', ok1 && ok2 && /Buy milk/.test(await textOf(page)) && /Walk dog/.test(await textOf(page)));
      const before = await page.evaluate(() => document.body.innerHTML);
      const ticked = await page.evaluate(() => {
        const item = [...document.querySelectorAll('li, .item, .todo, [class*=item], [class*=todo]')].find(e => /Buy milk/.test(e.textContent));
        if (!item) return false;
        const box = item.querySelector('input[type=checkbox]');
        if (box) { box.click(); return true; }
        const label = [...item.querySelectorAll('*')].find(e => e.children.length === 0 && /Buy milk/.test(e.textContent)) || item;
        label.click(); return true;
      });
      await wait(300);
      const after = await page.evaluate(() => document.body.innerHTML);
      check('ticks an item done', ticked && before !== after);
      const deleted = await page.evaluate(() => {
        const item = [...document.querySelectorAll('li, .item, .todo, [class*=item], [class*=todo]')].find(e => /Walk dog/.test(e.textContent));
        if (!item) return false;
        const del = [...item.querySelectorAll('button, [role=button], span, a')].find(e => /(✕|×|x|delete|remove|🗑|✖|❌)/i.test((e.textContent || e.getAttribute('aria-label') || '').trim()));
        if (!del) return false; del.click(); return true;
      });
      await wait(300);
      check('deletes an item', deleted && !/Walk dog/.test(await textOf(page)));
      await page.reload(); await wait(600);
      check('keeps the list after a reload', /Buy milk/.test(await textOf(page)));
    }

    if (kind === 'tictactoe') {
      const cells = async () => page.evaluate(() => {
        let c = [...document.querySelectorAll('[class*=cell], [data-index], [data-i], [data-cell], [data-pos], .square, .box')];
        if (c.length !== 9) { const board = [...document.querySelectorAll('*')].find(e => e.children.length === 9 && [...e.children].every(k => k.tagName === e.children[0].tagName)); c = board ? [...board.children] : c; }
        return c.length === 9 ? c.map(e => e.textContent.trim()) : null;
      });
      const tapCell = async i => page.evaluate(i => {
        let c = [...document.querySelectorAll('[class*=cell], [data-index], [data-i], [data-cell], [data-pos], .square, .box')];
        if (c.length !== 9) { const board = [...document.querySelectorAll('*')].find(e => e.children.length === 9 && [...e.children].every(k => k.tagName === e.children[0].tagName)); c = board ? [...board.children] : c; }
        c[i].click();
      }, i);
      const start = await cells();
      check('has a 3 × 3 board', start && start.every(s => s === ''), start ? start.join('|') : 'no 9 cells found');
      if (start) {
        await tapCell(0); await wait(900);
        const c1 = await cells();
        check('my tap puts an X and the computer answers with an O', c1[0] === 'X' && c1.filter(s => s === 'O').length === 1, c1.join('|'));
        let ended = false;
        for (let k = 0; k < 6 && !ended; k++) {
          const c = await cells(); const i = c.indexOf(''); if (i < 0) break;
          await tapCell(i); await wait(700);
          ended = /win|wins|won|draw|tie/i.test(await textOf(page));
        }
        check('a finished game says who won or that it is a draw', ended, (await textOf(page)).slice(0, 120));
        const tapped = await tapText(page, /(new game|restart|play again|reset)/);
        await wait(400);
        const c2 = await cells();
        check('New game clears the board', tapped && c2 && c2.every(s => s === ''), c2 ? c2.join('|') : '');
      }
    }

    if (kind === 'tip') {
      const inputs = await page.$$('input[type=number], input[inputmode=decimal], input[inputmode=numeric], input[type=text], input:not([type])');
      check('has inputs for the bill and the tip percent', inputs.length >= 2, inputs.length + ' inputs');
      if (inputs.length >= 2) {
        await inputs[0].fill('100'); await inputs[1].fill('15');
        for (const i of inputs.slice(0, 2)) await i.dispatchEvent('input');
        await tapText(page, /^(calculate|calc|compute|go|=)$/);
        await wait(400);
        const t = await shownText(page);
        check('a $100 bill at 15% shows a $15 tip', /(^|[^\d])15(\.00?)?($|[^\d.])/.test(t), t.slice(0, 160));
        check('and a $115 total', /(^|[^\d])115(\.00?)?($|[^\d.])/.test(t), t.slice(0, 160));
      }
    }
  } catch (e) { check('the checks ran to the end', false, e.message); }

  check('no errors while playing', errors.length === 0, errors.join('; '));
  await browser.close();
  console.log(JSON.stringify({ checks }));
})();
