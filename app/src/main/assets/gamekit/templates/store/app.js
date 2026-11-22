// A shop made from app.json: products with categories, search, a cart (quantities, stock limits, shipping and free-shipping
// threshold), a checkout form and a list of past orders. There is no payment: orders are saved on the device; connect a real
// payment provider before selling. Change products in app.json. Play tests read window.__app. Part of PocketIDE's app kit; MIT licensed.
import { h, $, toast, modal, fmt, theme, loadJSON } from './appkit/dom.js';
import { Store, Collection } from './appkit/store.js';

const cfg = await loadJSON('app.json', {});
theme(cfg); $('#title').textContent = cfg.title || 'Shop';
const P = cfg.products || [], money = n => fmt.money(n, cfg.currency), key = (cfg.title || 'shop').toLowerCase().replace(/\W+/g, '-');
const cart = { lines: Store.get(key + ':cart', {}) }, orders = new Collection(key + '-orders'), ui = { cat: '', q: '' };
const saveCart = () => { Store.set(key + ':cart', cart.lines); render(); };
const product = id => P.find(p => p.id === id);
const count = () => Object.values(cart.lines).reduce((a, b) => a + b, 0);
const subtotal = () => Object.entries(cart.lines).reduce((s, [id, n]) => s + (product(id)?.price || 0) * n, 0);
const shipping = () => { const s = cfg.shipping || {}; const sub = subtotal(); return !sub ? 0 : s.freeOver != null && sub >= s.freeOver ? 0 : (s.price || 0); };
function add(id, n = 1) {
  const p = product(id); if (!p) return false; const have = cart.lines[id] || 0, want = have + n;
  if (p.stock != null && want > p.stock) { toast('Only ' + p.stock + ' in stock'); return false; }
  if (want <= 0) delete cart.lines[id]; else cart.lines[id] = want; saveCart(); if (n > 0) toast('Added ' + p.name); return true;
}
function card(p) {
  return h('div', { class: 'card', 'data-product': p.id }, h('div', { style: { fontSize: '3rem', textAlign: 'center', padding: '10px 0', background: 'color-mix(in srgb,var(--accent) 10%,transparent)', borderRadius: '10px' } }, p.image ? h('img', { src: p.image, alt: p.name, style: { width: '100%', borderRadius: '8px' } }) : (p.emoji || '📦')),
    h('h3', { style: { marginTop: '10px' } }, p.name), h('p', { class: 'muted small' }, p.text || ''),
    h('div', { class: 'row' }, h('b', { class: 'grow' }, money(p.price)), p.stock === 0 ? h('span', { class: 'muted' }, 'Sold out') : h('button', { class: 'primary', 'data-add': p.id, onclick: () => add(p.id) }, 'Add')));
}
function openCart() {
  const body = () => h('div', { id: 'cart' }, count() ? [h('div', { class: 'list' }, Object.entries(cart.lines).map(([id, n]) => { const p = product(id); return h('div', { class: 'item' }, h('span', { style: { fontSize: '1.5rem' } }, p.emoji || '📦'), h('div', { class: 'grow' }, h('div', {}, p.name), h('div', { class: 'muted small' }, money(p.price) + ' each')),
      h('button', { class: 'ghost', 'aria-label': 'One less', onclick: () => { add(id, -1); refresh(); } }, '−'), h('b', {}, n), h('button', { class: 'ghost', 'aria-label': 'One more', onclick: () => { add(id, 1); refresh(); } }, '+')); })),
    h('div', { class: 'row', style: { marginTop: '10px' } }, h('span', { class: 'grow muted' }, 'Subtotal'), money(subtotal())), h('div', { class: 'row' }, h('span', { class: 'grow muted' }, 'Shipping'), shipping() ? money(shipping()) : 'Free'),
    h('div', { class: 'row', style: { fontWeight: 700 } }, h('span', { class: 'grow' }, 'Total'), h('span', { id: 'total' }, money(subtotal() + shipping())))] : h('p', { class: 'empty' }, 'Your cart is empty.'));
  let holder = h('div', {}, body()); const refresh = () => holder.replaceChildren(body());
  modal({ title: 'Your cart', body: holder, actions: [{ label: 'Keep shopping' }, ...(count() ? [{ label: 'Checkout', primary: true, onClick: () => { setTimeout(checkout, 0); } }] : [])] });
}
function checkout() {
  const c = cfg.checkout || { fields: [] };
  const form = h('form', { id: 'checkout' }, c.fields.map(f => h('label', { class: 'field' }, h('span', {}, f.label + (f.required ? ' *' : '')), f.type === 'textarea' ? h('textarea', { name: f.id }) : h('input', { name: f.id, type: f.type || 'text' }), h('div', { class: 'err', id: 'err-' + f.id }))), c.note ? h('p', { class: 'muted small' }, c.note) : null);
  modal({ title: 'Checkout · ' + money(subtotal() + shipping()), body: form, actions: [{ label: 'Back' }, { label: 'Place order', primary: true, onClick: () => {
    const data = {}; let ok = true;
    for (const f of c.fields) { const v = form.elements[f.id].value.trim(); data[f.id] = v; const bad = (f.required && !v) || (f.type === 'email' && v && !/^\S+@\S+\.\S+$/.test(v)); form.querySelector('#err-' + f.id).textContent = bad ? 'Please check this' : ''; if (bad) ok = false; }
    if (!ok) return false;
    orders.add({ ...data, lines: { ...cart.lines }, total: subtotal() + shipping() }); cart.lines = {}; saveCart(); toast('Order placed. Thank you!');
  } }] });
}
function render() {
  const list = P.filter(p => (!ui.cat || p.category === ui.cat) && (!ui.q || (p.name + ' ' + (p.text || '')).toLowerCase().includes(ui.q.toLowerCase())));
  $('#cartbtn').textContent = '🛒 ' + count();
  $('#main').replaceChildren(
    h('div', { class: 'row', style: { marginBottom: '10px' } }, h('input', { type: 'search', id: 'search', placeholder: 'Search', class: 'grow', style: { width: 'auto' }, value: ui.q, oninput: e => { ui.q = e.target.value; render(); $('#search').focus(); } })),
    cfg.categories ? h('div', { class: 'tabs' }, ['', ...cfg.categories].map(c => h('button', { class: ui.cat === c ? 'on' : '', 'data-cat': c || 'all', onclick: () => { ui.cat = c; render(); } }, c || 'All'))) : null,
    list.length ? h('div', { class: 'grid' }, list.map(card)) : h('div', { class: 'empty' }, 'No products match.'),
    orders.all().length ? h('details', { style: { marginTop: '20px' } }, h('summary', {}, 'Your orders (' + orders.all().length + ')'), h('div', { class: 'list' }, orders.all().map(o => h('div', { class: 'item' }, h('span', { class: 'grow' }, new Date(o.created).toLocaleString()), money(o.total))))) : null);
}
$('#actions').append(h('button', { id: 'cartbtn', onclick: openCart }, '🛒 0'));
render();
window.__app = { cfg, cart, orders, add, subtotal, shipping, count, render, openCart };
