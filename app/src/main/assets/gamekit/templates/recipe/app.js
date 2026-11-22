// A recipe box from app.json: recipes with ingredients and steps, search and tag filters, and a scaler: change the servings and every
// amount changes with it (1/2, 1 1/2 and 2.5 all work). Tick off ingredients while you cook. Saved on the device, or online with a
// cloud block. Import and export as JSON. Play tests read window.__app. Part of PocketIDE's app kit; MIT licensed.
import { h, $, toast, modal, confirmBox, theme, loadJSON, download, pickFile } from './appkit/dom.js';
import { openCollection, authButton } from './appkit/cloud.js';

const cfg = await loadJSON('app.json', {});
theme(cfg); $('#title').textContent = cfg.title || 'Recipe box';
const db = openCollection(cfg.storeName || 'recipes', cfg.sample || [], cfg);   // online when app.json has a cloud block
const ui = { q: '', tag: '', open: null };
const lines = s => String(s || '').split('\n').map(x => x.trim()).filter(Boolean);

// "1 1/2 cups flour" -> { qty: 1.5, rest: "cups flour" }; a line with no number is kept as it is
function parseAmount(line) {
  const m = String(line).match(/^(\d+\s+\d+\/\d+|\d+\/\d+|\d+(?:\.\d+)?)\s*(.*)$/);
  if (!m) return { qty: null, rest: line };
  const p = m[1].trim(); let qty;
  if (p.includes(' ')) { const [w, f] = p.split(/\s+/); const [n, d] = f.split('/'); qty = +w + (+n / +d); }
  else if (p.includes('/')) { const [n, d] = p.split('/'); qty = +n / +d; }
  else qty = parseFloat(p);
  return { qty, rest: m[2] };
}
// scaled text: whole and simple fractions read well, other amounts keep two decimals at most
function scaleLine(line, factor) {
  const { qty, rest } = parseAmount(line);
  if (qty == null || !isFinite(qty)) return line;
  const n = qty * factor, whole = Math.floor(n + 1e-9), frac = n - whole;
  const fr = [[0.25, '1/4'], [1 / 3, '1/3'], [0.5, '1/2'], [2 / 3, '2/3'], [0.75, '3/4']].find(([v]) => Math.abs(frac - v) < 0.02);
  const shown = fr ? (whole ? whole + ' ' + fr[1] : fr[1]) : (+n.toFixed(2)).toString();
  return shown + (rest ? ' ' + rest : '');
}

function filtered() {
  const q = ui.q.toLowerCase();
  return db.all().filter(r => (!q || (r.name + ' ' + lines(r.ingredients).join(' ')).toLowerCase().includes(q)) && (!ui.tag || (r.tags || []).includes(ui.tag)))
    .sort((a, b) => String(a.name).localeCompare(String(b.name)));
}
function form(rec) {
  const val = (k, d = '') => rec && rec[k] != null ? (Array.isArray(rec[k]) ? rec[k].join(k === 'ingredients' || k === 'steps' ? '\n' : ', ') : rec[k]) : d;
  const f = h('form', { onsubmit: e => e.preventDefault() },
    h('label', { class: 'field' }, h('span', {}, 'Name *'), h('input', { name: 'name', value: val('name') }), h('div', { class: 'err', id: 'err-name' })),
    h('label', { class: 'field' }, h('span', {}, 'Serves'), h('input', { name: 'servings', type: 'number', min: 1, value: val('servings', 4) })),
    h('label', { class: 'field' }, h('span', {}, 'Ingredients, one per line'), h('textarea', { name: 'ingredients' }, val('ingredients'))),
    h('label', { class: 'field' }, h('span', {}, 'Steps, one per line'), h('textarea', { name: 'steps' }, val('steps'))),
    h('label', { class: 'field' }, h('span', {}, 'Tags, comma separated'), h('input', { name: 'tags', value: val('tags') })));
  const save = () => {
    const v = n => f.querySelector(`[name="${n}"]`).value;
    const name = v('name').trim(); f.querySelector('#err-name').textContent = name ? '' : 'Please give it a name';
    if (!name) return false;
    const data = { name, servings: Math.max(1, +v('servings') || 4), ingredients: lines(v('ingredients')), steps: lines(v('steps')), tags: v('tags').split(',').map(s => s.trim()).filter(Boolean) };
    if (rec) { db.update(rec.id, data); toast('Saved'); } else { db.add(data); toast('Added ' + name); }
  };
  modal({ title: rec ? 'Edit recipe' : 'New recipe', body: f, actions: [...(rec ? [{ label: 'Delete', danger: true, onClick: () => confirmBox('Delete this recipe?').then(y => { if (y) { db.remove(rec.id); ui.open = null; toast('Deleted'); } }) }] : []), { label: 'Cancel' }, { label: 'Save', primary: true, onClick: save }] });
}

function detail(r) {
  const ticked = new Set();
  const scaleIn = h('input', { id: 'servings', type: 'number', min: 1, value: r.servings || 4, style: { width: '96px' } });
  const ings = h('div', { class: 'list' });
  const drawIngs = () => {
    const factor = (+scaleIn.value || r.servings || 4) / (r.servings || 4);
    ings.replaceChildren(...(r.ingredients || []).map((line, i) => h('label', { class: 'item', 'data-ing': i, style: { textDecoration: ticked.has(i) ? 'line-through' : '' } },
      h('input', { type: 'checkbox', checked: ticked.has(i), onchange: e => { e.target.checked ? ticked.add(i) : ticked.delete(i); drawIngs(); } }),
      h('span', {}, scaleLine(line, factor)))));
  };
  scaleIn.addEventListener('input', drawIngs); scaleIn.addEventListener('change', drawIngs); drawIngs();
  return h('div', {},
    h('div', { class: 'row', style: { marginBottom: '8px' } }, h('button', { class: 'ghost', onclick: () => { ui.open = null; render(); } }, '← All recipes'), h('button', { class: 'ghost right', onclick: () => form(r) }, 'Edit')),
    h('div', { class: 'card' }, h('h2', {}, r.name), (r.tags || []).length ? h('div', { class: 'row small' }, r.tags.map(t => h('span', { class: 'tag' }, t))) : null,
      h('div', { class: 'row', style: { margin: '12px 0' } }, h('label', { class: 'row' }, 'Serves ', scaleIn), h('span', { class: 'muted small' }, 'amounts change as you change this')),
      h('h3', {}, 'Ingredients'), ings,
      h('h3', { style: { marginTop: '14px' } }, 'Steps'),
      h('ol', { style: { paddingLeft: '20px' } }, (r.steps || []).map(s => h('li', { style: { margin: '6px 0' } }, s)))));
}

function render() {
  const main = $('#main');
  if (ui.open) { const r = db.get(ui.open); if (r) return main.replaceChildren(detail(r)); ui.open = null; }
  const rows = filtered(), tags = [...new Set(db.all().flatMap(r => r.tags || []))].sort();
  main.replaceChildren(
    h('div', { class: 'row', style: { marginBottom: '12px' } },
      h('input', { type: 'search', id: 'search', placeholder: 'Search by name or ingredient', value: ui.q, class: 'grow', oninput: e => { ui.q = e.target.value; render(); $('#search').focus(); } })),
    tags.length ? h('div', { class: 'row small', style: { marginBottom: '12px' } }, h('button', { class: ui.tag ? 'ghost' : 'primary', onclick: () => { ui.tag = ''; render(); } }, 'All'),
      tags.map(t => h('button', { class: ui.tag === t ? 'primary' : 'ghost', onclick: () => { ui.tag = t; render(); } }, t))) : null,
    rows.length ? h('div', { class: 'grid' }, rows.map(r => h('div', { class: 'card', 'data-recipe': r.id, style: { cursor: 'pointer' }, onclick: () => { ui.open = r.id; render(); } },
      h('h3', {}, r.name), h('div', { class: 'muted small' }, `Serves ${r.servings || '?'} · ${(r.ingredients || []).length} ingredients`),
      (r.tags || []).length ? h('div', { class: 'row small', style: { marginTop: '6px' } }, r.tags.map(t => h('span', { class: 'tag' }, t))) : null)))
      : h('div', { class: 'empty' }, db.all().length ? 'No recipes match.' : 'No recipes yet. Tap + to add one.'),
    h('button', { class: 'primary fab', id: 'add', 'aria-label': 'Add recipe', onclick: () => form(null) }, '+'));
}

const auth = authButton(cfg, { h, modal, toast }); if (auth) $('#actions').append(auth);
$('#actions').append(
  h('button', { class: 'ghost', title: 'Export', onclick: () => download('recipes.json', JSON.stringify(db.all(), null, 2), 'application/json') }, '⤓'),
  h('button', { class: 'ghost', title: 'Import', onclick: async () => { const t = await pickFile('.json'); if (!t) return; try { const rows = JSON.parse(t); db.replace(rows); toast('Imported ' + rows.length); } catch (e) { toast('Could not read that file'); } } }, '⤒'));
db.onChange = render; render();
window.__app = { cfg, db, render, items: () => db.all(), ui, scale: parseAmount, scaleLine };
