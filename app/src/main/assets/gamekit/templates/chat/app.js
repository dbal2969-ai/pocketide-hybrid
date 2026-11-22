// Chat rooms from app.json: messages in rooms, your name, your own messages can be deleted. With a cloud block (app.json) and
// sign-in, rooms are shared: everyone signed in sees the messages, checked every few seconds. Without it, messages stay on this
// device. Play tests read window.__app. Part of PocketIDE's app kit; MIT licensed.
import { h, $, toast, modal, theme, loadJSON, fmt } from './appkit/dom.js';
import { Store } from './appkit/store.js';
import { openCollection, authButton, cloudFor } from './appkit/cloud.js';

const cfg = await loadJSON('app.json', {});
theme(cfg); $('#title').textContent = cfg.title || 'Chat';
const ROOMS = cfg.rooms || ['general'];
const online = !!cloudFor(cfg);
// shared rooms are stored under a shared: name so the database lets every signed-in person read them
const db = openCollection(cfg.storeName || 'shared:chat', cfg.sample || [], cfg, { poll: online ? 4000 : 0 });
const ui = { room: ROOMS[0], name: Store.get('chat:name', '') };
// who wrote a message: the signed-in account, or this device before signing in
function me() { const u = cloudFor(cfg) && cloudFor(cfg).user; if (u) return u.id; let d = Store.get('chat:device', null); if (!d) { d = Math.random().toString(36).slice(2); Store.set('chat:device', d); } return 'device:' + d; }

const tabs = h('div', { class: 'tabs', role: 'tablist' });
const list = h('div', { class: 'card', id: 'messages', style: { height: '56vh', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '8px' } });
const nameIn = h('input', { name: 'name', placeholder: 'Your name', value: ui.name, style: { width: 'auto', flex: '0 1 150px' }, oninput: e => { ui.name = e.target.value.trim(); Store.set('chat:name', ui.name); } });
const textIn = h('input', { name: 'text', placeholder: 'Message', autocomplete: 'off', class: 'grow', onkeydown: e => { if (e.key === 'Enter') { e.preventDefault(); send(); } } });
const sendBtn = h('button', { class: 'primary', id: 'send', onclick: () => send() }, 'Send');
const note = h('p', { class: 'muted small', style: { margin: '8px 2px' } }, online ? 'Shared with everyone signed in.' : 'Messages stay on this device. Add a cloud block to app.json and sign in to chat with others.');

$('#main').replaceChildren(tabs, list, note, h('div', { class: 'row', style: { marginTop: '10px' } }, nameIn, textIn, sendBtn));

function renderTabs() {
  tabs.replaceChildren(...ROOMS.map(r => h('button', { class: r === ui.room ? 'on' : '', onclick: () => { ui.room = r; render(); } }, r)));
}
function render() {
  renderTabs();
  const msgs = db.all().filter(m => m.room === ui.room).reverse();   // oldest first
  list.replaceChildren(...(msgs.length ? msgs.map(m => {
    const mine = m.by === me();
    return h('div', { 'data-msg': m.id, class: 'card', style: { alignSelf: mine ? 'flex-end' : 'flex-start', maxWidth: '82%', padding: '8px 12px', background: mine ? 'var(--accent)' : '', color: mine ? '#fff' : '' } },
      h('div', { class: 'small', style: { opacity: .8, fontWeight: 600 } }, (m.name || 'Guest') + ' · ' + new Date(m.at || m.created || Date.now()).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })),
      h('div', { 'data-text': '' }, m.text),
      mine ? h('button', { class: 'ghost small', style: { minHeight: '28px', padding: '2px 6px', color: 'inherit' }, onclick: () => db.remove(m.id) }, 'Delete') : null);
  }) : [h('div', { class: 'empty' }, 'No messages in ' + ui.room + ' yet.')]));
  list.scrollTop = list.scrollHeight;
}
function send() {
  const text = textIn.value.trim(); if (!text) return;
  db.add({ room: ui.room, name: ui.name || 'Guest', by: me(), text, at: new Date().toISOString() });
  textIn.value = ''; textIn.focus();
}

const auth = authButton(cfg, { h, modal, toast }); if (auth) $('#actions').append(auth);
db.onChange = render; render();
window.__app = { cfg, db, items: () => db.all(), render, send: t => { textIn.value = t; send(); }, room: () => ui.room, ui };
