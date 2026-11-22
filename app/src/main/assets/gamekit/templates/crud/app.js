// A tracker made from app.json: records with the fields you list (text, textarea, number, money, date, select, check, tags, rating,
// email, url), a list and a board (columns from the select field marked "board"), search, filters, sorting, stats, add / edit /
// delete, and export / import (CSV and JSON). Saved on the device. Change what it tracks by editing app.json, not this file.
// Play tests read window.__app. Part of PocketIDE's app kit; MIT licensed.
import { h, $, toast, modal, confirmBox, fmt, today, download, pickFile, theme, loadJSON } from './appkit/dom.js';
import { toCSV, fromCSV } from './appkit/store.js';
import { openCollection, authButton } from './appkit/cloud.js';

const cfg = await loadJSON('app.json', {});
theme(cfg); $('#title').textContent = cfg.title || 'Tracker';
const F = cfg.fields || [{ id: 'title', label: 'Title', type: 'text', required: true }];
const noun = cfg.item || 'item', boardField = F.find(f => f.board && f.type === 'select');
const db = openCollection(cfg.storeName || (cfg.title || 'items').toLowerCase().replace(/\W+/g, '-'), cfg.sample || [], cfg);   // online when app.json has a cloud block
const ui = { view: boardField && cfg.view === 'board' ? 'board' : 'list', q: '', filters: {}, sort: (cfg.sort && cfg.sort[0]) || null };

function show(f, v) {
  if (v == null || v === '' || (Array.isArray(v) && !v.length)) return '';
  if (f.type === 'date') return fmt.date(v);
  if (f.type === 'money') return fmt.money(+v, cfg.currency);
  if (f.type === 'check') return v ? '✓ ' + f.label : '';
  if (f.type === 'rating') return '★'.repeat(+v) + '☆'.repeat(Math.max(0, (f.max || 5) - v));
  if (f.type === 'tags') return [].concat(v).map(t => '#' + t).join(' ');
  return String(v);
}
const matches = r => {
  if (ui.q && !F.some(f => show(f, r[f.id]).toLowerCase().includes(ui.q.toLowerCase()))) return false;
  return Object.entries(ui.filters).every(([k, v]) => !v || r[k] === v);
};
const PRIO = ['High', 'Medium', 'Low'];
function sorted(rows) {
  const s = ui.sort; if (!s) return rows; const f = F.find(x => x.id === s.field) || { type: 'text' };
  const key = r => { const v = r[s.field]; if (v == null || v === '') return s.desc ? -Infinity : Infinity; if (f.options) return f.options.indexOf(v) * (f.id === 'priority' ? 1 : 1); if (f.type === 'number' || f.type === 'money' || f.type === 'rating') return +v; return String(v); };
  const k = (a, b) => { let x = key(a), y = key(b); if (s.field === 'priority') { x = PRIO.indexOf(a.priority); y = PRIO.indexOf(b.priority); } return x < y ? -1 : x > y ? 1 : 0; };
  return rows.slice().sort((a, b) => s.desc ? -k(a, b) : k(a, b));
}
function stat(s) {
  const rows = db.all();
  if (s.sum) return fmt.num(rows.reduce((n, r) => n + (+r[s.sum] || 0), 0), 2);
  return rows.filter(r => Object.entries(s.count || {}).every(([k, v]) => Array.isArray(v) ? v.includes(r[k]) : r[k] === v)).length;
}

// ---- the form for one record ----
function input(f, v) {
  const val = v ?? f.default ?? '';
  if (f.type === 'textarea') return h('textarea', { name: f.id }, val);
  if (f.type === 'select') return h('select', { name: f.id }, h('option', { value: '' }, '—'), f.options.map(o => h('option', { value: o, selected: o === val }, o)));
  if (f.type === 'check') return h('input', { type: 'checkbox', name: f.id, checked: !!val });
  if (f.type === 'tags') return h('input', { name: f.id, value: [].concat(val || []).join(', '), placeholder: 'comma, separated' });
  if (f.type === 'rating') return h('select', { name: f.id }, [0, 1, 2, 3, 4, 5].slice(0, (f.max || 5) + 1).map(n => h('option', { value: n, selected: +val === n }, n ? '★'.repeat(n) : 'none')));
  const type = { number: 'number', money: 'number', date: 'date', email: 'email', url: 'url' }[f.type] || 'text';
  return h('input', { type, name: f.id, value: val, step: f.type === 'money' ? '0.01' : 'any', placeholder: f.placeholder || '' });
}
function read(form) {
  const r = {};
  for (const f of F) { const e = form.querySelector(`[name="${f.id}"]`); if (!e) continue;
    r[f.id] = f.type === 'check' ? e.checked : f.type === 'tags' ? e.value.split(',').map(s => s.trim()).filter(Boolean) : (f.type === 'number' || f.type === 'money' || f.type === 'rating') ? (e.value === '' ? null : +e.value) : e.value; }
  return r;
}
function edit(rec) {
  const form = h('form', { onsubmit: e => e.preventDefault() }, F.map(f => h('label', { class: 'field' }, h('span', {}, f.label + (f.required ? ' *' : '')), input(f, rec ? rec[f.id] : (f.type === 'date' && f.today ? today() : undefined)), h('div', { class: 'err', id: 'err-' + f.id }))));
  const save = () => {
    const r = read(form); let ok = true;
    for (const f of F) { const bad = f.required && (r[f.id] == null || r[f.id] === '' || (Array.isArray(r[f.id]) && !r[f.id].length)); form.querySelector('#err-' + f.id).textContent = bad ? f.label + ' is needed' : ''; if (bad) ok = false; }
    if (!ok) return false;
    if (rec) { db.update(rec.id, r); toast('Saved'); } else { db.add(r); toast('Added ' + noun); }
  };
  modal({ title: (rec ? 'Edit ' : 'New ') + noun, body: form, actions: [...(rec ? [{ label: 'Delete', danger: true, onClick: () => { confirmBox('Delete this ' + noun + '?').then(y => { if (y) { db.remove(rec.id); toast('Deleted'); } }); } }] : []), { label: 'Cancel' }, { label: rec ? 'Save' : 'Add', primary: true, onClick: save }] });
}

// ---- the page ----
const T = cfg.list || {};
const titleF = F.find(f => f.id === T.title) || F[0], doneF = F.find(f => f.done != null);
function row(r) {
  const sub = (T.subtitle || []).map(id => show(F.find(f => f.id === id) || {}, r[id])).filter(Boolean).join(' · ');
  const badgeF = F.find(f => f.id === T.badge), isDone = doneF && r[doneF.id] === doneF.done;
  return h('div', { class: 'item', 'data-id': r.id },
    doneF ? h('input', { type: 'checkbox', checked: isDone, 'aria-label': 'Done', onchange: e => db.update(r.id, { [doneF.id]: e.target.checked ? doneF.done : (doneF.options || [])[0] }) }) : null,
    h('div', { class: 'grow', onclick: () => edit(r), style: { cursor: 'pointer' } }, h('div', { style: { fontWeight: 600, textDecoration: isDone ? 'line-through' : '' } }, show(titleF, r[titleF.id]) || '(untitled)'), sub ? h('div', { class: 'muted small' }, sub) : null),
    badgeF && r[badgeF.id] ? h('span', { class: 'tag' }, r[badgeF.id]) : null);
}
function board(rows) {
  return h('div', { class: 'board' }, boardField.options.map(o => {
    const col = h('div', { class: 'col', 'data-col': o, ondragover: e => { e.preventDefault(); col.classList.add('over'); }, ondragleave: () => col.classList.remove('over'),
      ondrop: e => { e.preventDefault(); col.classList.remove('over'); const id = e.dataTransfer.getData('text'); if (id) db.update(id, { [boardField.id]: o }); } },
      h('h3', {}, o + ' (' + rows.filter(r => r[boardField.id] === o).length + ')'),
      rows.filter(r => r[boardField.id] === o).map(r => h('div', { class: 'card', draggable: true, 'data-id': r.id, ondragstart: e => e.dataTransfer.setData('text', r.id), onclick: () => edit(r) },
        h('div', { style: { fontWeight: 600 } }, show(titleF, r[titleF.id])), h('div', { class: 'muted small' }, (T.subtitle || []).map(id => show(F.find(f => f.id === id) || {}, r[id])).filter(Boolean).join(' · ')),
        h('div', { class: 'row small', style: { marginTop: '6px' } }, boardField.options.filter(x => x !== o).map(x => h('button', { class: 'ghost small', style: { minHeight: '30px', padding: '2px 8px' }, onclick: e => { e.stopPropagation(); db.update(r.id, { [boardField.id]: x }); } }, '→ ' + x))))));
    return col;
  }));
}
function render() {
  const rows = sorted(db.all().filter(matches)), main = $('#main');
  const filters = F.filter(f => f.type === 'select' && f.filter !== false).map(f => h('select', { 'aria-label': 'Filter by ' + f.label, onchange: e => { ui.filters[f.id] = e.target.value; render(); } },
    h('option', { value: '' }, 'All ' + f.label.toLowerCase()), f.options.map(o => h('option', { value: o, selected: ui.filters[f.id] === o }, o))));
  main.replaceChildren(
    cfg.stats ? h('div', { class: 'grid', style: { gridTemplateColumns: 'repeat(auto-fit,minmax(100px,1fr))', marginBottom: '12px' } }, cfg.stats.map(s => h('div', { class: 'card kpi', 'data-stat': s.label }, h('small', {}, s.label), stat(s)))) : null,
    h('div', { class: 'row', style: { marginBottom: '12px' } },
      h('input', { type: 'search', id: 'search', placeholder: 'Search', value: ui.q, class: 'grow', style: { width: 'auto' }, oninput: e => { ui.q = e.target.value; render(); $('#search').focus(); } }),
      ...filters,
      cfg.sort ? h('select', { 'aria-label': 'Sort', onchange: e => { ui.sort = cfg.sort[+e.target.value]; render(); } }, cfg.sort.map((s, i) => h('option', { value: i, selected: ui.sort === s }, 'Sort: ' + s.label))) : null),
    boardField ? h('div', { class: 'tabs' }, ['list', 'board'].map(v => h('button', { class: ui.view === v ? 'on' : '', onclick: () => { ui.view = v; render(); } }, v === 'list' ? 'List' : 'Board'))) : null,
    rows.length ? (ui.view === 'board' && boardField ? board(rows) : h('div', { class: 'card list', style: { padding: '0 10px' } }, rows.map(row)))
      : h('div', { class: 'empty' }, db.all().length ? 'Nothing matches.' : 'No ' + noun + 's yet. Tap + to add one.'),
    h('button', { class: 'primary fab', id: 'add', 'aria-label': 'Add ' + noun, onclick: () => edit(null) }, '+'));
}
const auth = authButton(cfg, { h, modal, toast }); if (auth) $('#actions').append(auth);
$('#actions').append(
  h('button', { class: 'ghost', title: 'Export', onclick: () => modal({ title: 'Export', body: h('p', { class: 'muted' }, 'Save a copy of your ' + noun + 's.'), actions: [{ label: 'CSV', onClick: () => download((cfg.title || 'data') + '.csv', toCSV(db.all(), ['id', ...F.map(f => f.id)]), 'text/csv') }, { label: 'JSON', onClick: () => download((cfg.title || 'data') + '.json', JSON.stringify(db.all(), null, 2), 'application/json') }, { label: 'Close' }] }) }, '⤓'),
  h('button', { class: 'ghost', title: 'Import', onclick: async () => { const t = await pickFile('.csv,.json'); if (!t) return; try { const rows = t.trim().startsWith('[') ? JSON.parse(t) : fromCSV(t); db.replace(rows); toast('Imported ' + rows.length); } catch (e) { toast('Could not read that file'); } } }, '⤒'));
db.onChange = render; render();
window.__app = { cfg, db, render, items: () => db.all(), add: r => db.add(r), ui };
