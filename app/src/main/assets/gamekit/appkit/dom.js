// Small helpers for apps without a framework: build elements, toasts, a bottom-sheet dialog, confirm, a hash router,
// number/date formatting, file download and pick. Part of PocketIDE's app kit; MIT licensed.
//   h('button', { class: 'primary', onclick: save }, 'Save')  ->  <button class="primary">Save</button>
export const $ = (s, r = document) => r.querySelector(s);
export const $$ = (s, r = document) => [...r.querySelectorAll(s)];

export function h(tag, attrs = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.slice(2), v);
    else if (k === 'style' && typeof v === 'object') Object.assign(e.style, v);
    else if (k === 'class') e.className = v;
    else if (k in e && k !== 'list' && typeof v !== 'string') e[k] = v;
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat(Infinity)) if (c != null && c !== false) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return e;
}

let toastTimer = 0;
export function toast(msg, ms = 2200) {
  let t = $('.toast'); if (!t) { t = h('div', { class: 'toast', role: 'status' }); document.body.append(t); }
  t.textContent = msg; t.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, ms);
}

// A dialog that slides up on phones. body: an element; actions: [{ label, primary, danger, onClick() -> false keeps it open }]. Returns close().
export function modal({ title = '', body = null, actions = [{ label: 'Close' }] } = {}) {
  const close = () => back.remove();
  const box = h('div', { class: 'modal', role: 'dialog', 'aria-label': title }, title ? h('h2', {}, title) : null, body,
    h('div', { class: 'row', style: { marginTop: '14px', justifyContent: 'flex-end' } },
      actions.map(a => h('button', { class: a.primary ? 'primary' : a.danger ? 'danger' : '', onclick: () => { if (a.onClick && a.onClick() === false) return; close(); } }, a.label))));
  const back = h('div', { class: 'modal-back', onclick: e => { if (e.target === back) close(); } }, box);
  document.body.append(back);
  const first = box.querySelector('input,select,textarea'); if (first) setTimeout(() => first.focus(), 30);
  return close;
}
export const confirmBox = (text, yes = 'Delete') => new Promise(res => modal({ title: text, actions: [{ label: 'Cancel', onClick: () => res(false) }, { label: yes, danger: true, onClick: () => res(true) }] }));

// routes: { name: () => element }. Shows routes[location.hash] in root; #/ is the first route.
export function router(routes, root, onShow = () => {}) {
  const names = Object.keys(routes);
  const show = () => { const name = location.hash.replace(/^#\/?/, '') || names[0]; const r = routes[name] || routes[names[0]]; root.replaceChildren(r()); onShow(name); window.scrollTo(0, 0); };
  addEventListener('hashchange', show); show();
  return { go: name => { location.hash = '#/' + name; }, refresh: show };
}

export const fmt = {
  money: (n, cur = 'USD') => { try { return new Intl.NumberFormat(undefined, { style: 'currency', currency: cur }).format(n || 0); } catch (e) { return (n || 0).toFixed(2); } },
  num: (n, d = 0) => (n || 0).toLocaleString(undefined, { maximumFractionDigits: d }),
  date: d => d ? new Date(d + (String(d).length === 10 ? 'T00:00' : '')).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' }) : '',
  pct: n => ((n || 0) * 100).toFixed(0) + '%'
};
export const today = () => new Date().toISOString().slice(0, 10);
export const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);

export function download(name, text, type = 'text/plain') {
  const a = h('a', { href: URL.createObjectURL(new Blob([text], { type })), download: name }); document.body.append(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 500);
}
export const pickFile = (accept = '') => new Promise(res => { const i = h('input', { type: 'file', accept, onchange: () => { const f = i.files[0]; if (!f) return res(null); const r = new FileReader(); r.onload = () => res(String(r.result)); r.readAsText(f); } }); i.click(); });

// sets --accent and the page title from app.json
export function theme(cfg = {}) { if (cfg.accent) document.documentElement.style.setProperty('--accent', cfg.accent); if (cfg.title) document.title = cfg.title; if (cfg.theme) document.documentElement.dataset.theme = cfg.theme; }
export async function loadJSON(url, fallback = null) { try { const r = await fetch(url); return r.ok ? await r.json() : fallback; } catch (e) { return fallback; } }
