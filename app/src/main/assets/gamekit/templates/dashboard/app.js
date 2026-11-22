// A dashboard made from app.json and a data file (CSV or JSON rows): KPI tiles, line / bar / donut charts, filters and a sortable
// table. Group by a field or by month(date) / year(date); sum, avg, count, min or max. Change what it shows in app.json and swap
// data.csv for your own data (same columns as the config names). Play tests read window.__app. Part of PocketIDE's app kit; MIT licensed.
import { h, $, fmt, theme, loadJSON, download } from './appkit/dom.js';
import { fromCSV, toCSV } from './appkit/store.js';
import { lineChart, barChart, donutChart, PALETTE } from './appkit/charts.js';

const cfg = await loadJSON('app.json', {});
theme(cfg); $('#title').textContent = cfg.title || 'Dashboard';
let rows = cfg.rows || [];
if (cfg.data) { try { const r = await fetch(cfg.data); if (r.ok) { const t = await r.text(); rows = cfg.data.endsWith('.json') ? JSON.parse(t) : fromCSV(t, { numbers: true }); } } catch (e) { /* keep rows */ } }
const ui = { filters: {}, sort: null, desc: true };

const keyOf = (r, x) => { const m = /^(month|year|day)\((\w+)\)$/.exec(x); if (!m) return r[x]; const d = String(r[m[2]] || ''); return m[1] === 'month' ? d.slice(0, 7) : m[1] === 'year' ? d.slice(0, 4) : d.slice(0, 10); };
function agg(list, field, how = 'sum') {
  if (how === 'count') return list.length;
  const v = list.map(r => +r[field] || 0); if (!v.length) return 0;
  if (how === 'avg') return v.reduce((a, b) => a + b, 0) / v.length;
  if (how === 'min') return Math.min(...v); if (how === 'max') return Math.max(...v);
  return v.reduce((a, b) => a + b, 0);
}
function group(list, x, y, how) {
  const g = new Map(); for (const r of list) { const k = keyOf(r, x); if (!g.has(k)) g.set(k, []); g.get(k).push(r); }
  const keys = [...g.keys()].sort((a, b) => /\(/.test(x) ? String(a).localeCompare(String(b)) : agg(g.get(b), y, how) - agg(g.get(a), y, how));
  return { labels: keys, values: keys.map(k => agg(g.get(k), y, how)), groups: g };
}
const show = (v, f) => f === 'money' ? (Math.abs(v) >= 10000 ? fmt.money(Math.round(v), cfg.currency).replace(/[.,]00$/, '') : fmt.money(v, cfg.currency)) : fmt.num(v, f === 'avg' ? 1 : 0);   // no cents on big numbers, so tiles fit a phone
const filtered = () => rows.filter(r => Object.entries(ui.filters).every(([k, v]) => !v || String(r[k]) === v));

function render() {
  const list = filtered(), main = $('#main');
  const filters = (cfg.filters || []).map(f => h('select', { 'aria-label': f.label || f.field, 'data-filter': f.field, onchange: e => { ui.filters[f.field] = e.target.value; render(); } },
    h('option', { value: '' }, 'All ' + (f.label || f.field).toLowerCase() + 's'), [...new Set(rows.map(r => String(r[f.field])))].sort().map(o => h('option', { value: o, selected: ui.filters[f.field] === o }, o))));
  const charts = (cfg.charts || []).map(c => {
    let chart;
    if (c.type === 'line') {
      const all = group(list, c.x, c.y, c.agg), labels = all.labels.slice().sort();
      const splits = c.split ? [...new Set(list.map(r => r[c.split]))].sort() : [null];
      chart = lineChart({ labels, series: splits.map(sv => { const sub = sv == null ? list : list.filter(r => r[c.split] === sv), g = group(sub, c.x, c.y, c.agg); return { name: sv || c.y, values: labels.map(l => { const i = g.labels.indexOf(l); return i < 0 ? 0 : g.values[i]; }) }; }) });
      if (c.split) chart.append(h('div', { class: 'row small muted' }, splits.map((s, i) => h('span', {}, h('b', { style: { color: PALETTE[i % PALETTE.length] } }, '● '), s))));
    } else { const g = group(list, c.x, c.y, c.agg); chart = c.type === 'donut' ? donutChart(g) : barChart(g); }
    return h('div', { class: 'card', 'data-chart': c.title }, h('h3', {}, c.title || c.y), chart);
  });
  const t = cfg.table || {}, cols = t.fields || Object.keys(rows[0] || {});
  const trows = ui.sort ? list.slice().sort((a, b) => { const x = a[ui.sort], y = b[ui.sort]; return (x < y ? -1 : x > y ? 1 : 0) * (ui.desc ? -1 : 1); }) : list;
  main.replaceChildren(
    filters.length ? h('div', { class: 'row', style: { marginBottom: '12px' } }, ...filters, h('span', { class: 'muted small right' }, list.length + ' rows')) : null,
    h('div', { class: 'grid', style: { gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))', marginBottom: '12px' } }, (cfg.kpis || []).map(k => h('div', { class: 'card kpi', 'data-kpi': k.label }, h('small', {}, k.label), show(agg(list, k.field, k.agg), k.format || (k.agg === 'avg' ? 'avg' : ''))))),
    h('div', { class: 'grid', style: { gridTemplateColumns: 'repeat(auto-fit,minmax(300px,1fr))', marginBottom: '12px' } }, charts),
    h('div', { class: 'card', style: { overflowX: 'auto' } }, h('div', { class: 'row' }, h('h3', { class: 'grow' }, t.title || 'Data'), h('button', { class: 'ghost small', onclick: () => download('data.csv', toCSV(list, cols), 'text/csv') }, 'Export CSV')),
      h('table', {}, h('thead', {}, h('tr', {}, cols.map(c => h('th', { onclick: () => { ui.desc = ui.sort === c ? !ui.desc : true; ui.sort = c; render(); } }, c + (ui.sort === c ? (ui.desc ? ' ↓' : ' ↑') : ''))))),
        h('tbody', {}, trows.slice(0, t.rows || 50).map(r => h('tr', {}, cols.map(c => h('td', {}, (t.money || []).includes(c) ? fmt.money(+r[c], cfg.currency) : String(r[c] ?? '')))))))));
}
render();
window.__app = { cfg, rows: () => rows, filtered, ui, render, agg, group };
