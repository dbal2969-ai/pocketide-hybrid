// A website made from app.json: navigation, a hero, features, steps, pricing, FAQ, a contact form and a footer. Pick and order the
// sections in "sections"; each one's text lives under its own name. The contact form checks its fields, keeps messages on the device
// (window.__app.messages()) and opens the visitor's mail app when an email address is set. Part of PocketIDE's app kit; MIT licensed.
import { h, $, toast, theme, loadJSON } from './appkit/dom.js';
import { Store } from './appkit/store.js';

const cfg = await loadJSON('app.json', {});
theme(cfg); if (cfg.description) document.querySelector('meta[name=description]').content = cfg.description;
const label = { features: 'Features', steps: 'How it works', pricing: 'Pricing', faq: 'FAQ', contact: 'Contact', gallery: 'Gallery', about: 'About' };
const SECTIONS = {
  hero: c => h('section', { class: 'hero', id: 'hero' }, h('div', { class: 'in' }, h('h1', {}, c.title), h('p', {}, c.text),
    h('div', { class: 'row', style: { justifyContent: 'center' } }, c.cta ? h('a', { class: 'btn primary', href: c.cta.href }, c.cta.label) : null, c.secondary ? h('a', { class: 'btn', href: c.secondary.href }, c.secondary.label) : null))),
  features: c => block('features', c, h('div', { class: 'grid' }, (c.items || []).map(f => h('div', { class: 'card feature' }, h('div', { class: 'icon' }, f.icon || '★'), h('h3', {}, f.title), h('p', { class: 'muted' }, f.text))))),
  steps: c => block('steps', c, h('div', { class: 'grid steps' }, (c.items || []).map(s => h('div', { class: 'step' }, h('h3', {}, s.title), h('p', { class: 'muted' }, s.text))))),
  about: c => block('about', c, h('div', {}, [].concat(c.text || []).map(p => h('p', {}, p)))),
  gallery: c => block('gallery', c, h('div', { class: 'grid' }, (c.items || []).map(g => h('figure', { class: 'card', style: { margin: 0 } }, g.image ? h('img', { src: g.image, alt: g.caption || '', style: { width: '100%', borderRadius: '8px' } }) : h('div', { style: { height: '140px', borderRadius: '8px', background: g.color || 'color-mix(in srgb,var(--accent) 25%,var(--bg))' } }), h('figcaption', { class: 'muted small' }, g.caption || ''))))),
  pricing: c => block('pricing', c, h('div', { class: 'grid' }, (c.items || []).map(p => h('div', { class: 'card price' + (p.best ? ' best' : '') }, p.best ? h('span', { class: 'tag' }, 'Most popular') : null, h('h3', {}, p.name), h('div', { class: 'amount' }, p.price), h('ul', {}, (p.features || []).map(f => h('li', {}, f))), h('a', { class: 'btn' + (p.best ? ' primary' : ''), href: '#contact', style: { width: '100%' } }, p.cta || 'Choose'))))),
  faq: c => block('faq', c, h('div', {}, (c.items || []).map(q => h('details', {}, h('summary', {}, q.q), h('p', { class: 'muted' }, q.a))))),
  contact: c => {
    const form = h('form', { class: 'card', id: 'contact-form', novalidate: true, style: { maxWidth: '560px' } }, (c.fields || []).map(f => h('label', { class: 'field' }, h('span', {}, f.label + (f.required ? ' *' : '')),
      f.type === 'textarea' ? h('textarea', { name: f.id }) : h('input', { name: f.id, type: f.type || 'text' }), h('div', { class: 'err', id: 'err-' + f.id }))), h('button', { class: 'primary', type: 'submit' }, c.button || 'Send'));
    form.addEventListener('submit', e => {
      e.preventDefault(); const data = {}; let ok = true;
      for (const f of c.fields || []) { const v = form.elements[f.id].value.trim(); data[f.id] = v; const bad = (f.required && !v) || (f.type === 'email' && v && !/^\S+@\S+\.\S+$/.test(v)); $('#err-' + f.id).textContent = bad ? (v ? 'Please check this' : f.label + ' is needed') : ''; if (bad) ok = false; }
      if (!ok) return;
      const all = Store.get('messages', []); all.push({ ...data, at: new Date().toISOString() }); Store.set('messages', all);
      form.replaceChildren(h('h3', {}, c.thanks || 'Thank you! We will be in touch.'));
      toast('Message sent');
      if (c.email && !window.__app.testing) location.href = 'mailto:' + c.email + '?subject=' + encodeURIComponent('Message from ' + (data.name || 'the website')) + '&body=' + encodeURIComponent(Object.values(data).join('\n\n'));
    });
    return block('contact', c, h('div', {}, c.text ? h('p', { class: 'muted' }, c.text) : null, form));
  }
};
let alt = false;
function block(id, c, body) { alt = !alt; return h('section', { id, class: alt ? 'alt' : '' }, h('div', { class: 'in' }, h('h2', {}, c.title || label[id] || id), body)); }
const order = (cfg.sections || Object.keys(SECTIONS)).filter(s => cfg[s] && SECTIONS[s]);
const links = h('div', { class: 'links' }, order.filter(s => s !== 'hero').map(s => h('a', { href: '#' + s, onclick: () => links.classList.remove('open') }, (cfg[s].nav || label[s] || s))));
$('#site').replaceChildren(
  h('nav', { class: 'top' }, h('a', { class: 'brand', href: '#hero' }, cfg.brand || cfg.title || ''), links, h('button', { id: 'menu', class: 'ghost', 'aria-label': 'Menu', onclick: () => links.classList.toggle('open') }, '☰')),
  ...order.map(s => SECTIONS[s](cfg[s])),
  h('footer', {}, cfg.footer || ''));
window.__app = { cfg, sections: order, messages: () => Store.get('messages', []), testing: false };
