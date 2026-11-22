// An AI assistant chat from app.json. It never holds an API key: "endpoint" is a URL on a server you run (or a serverless function)
// that calls the model and returns { "reply": "..." } or an OpenAI-style { "choices": [...] }. Without an endpoint it runs in demo mode.
// The conversation stays on this device. Play tests read window.__app. Part of PocketIDE's app kit; MIT licensed.
import { h, $, toast, modal, confirmBox, theme, loadJSON } from './appkit/dom.js';
import { Collection } from './appkit/store.js';
import { authButton } from './appkit/cloud.js';

const cfg = await loadJSON('app.json', {});
theme(cfg); $('#title').textContent = cfg.title || 'Assistant';
const db = new Collection(cfg.storeName || 'assistant-chat');   // device only: the conversation is not uploaded anywhere by this app
const ui = { busy: false };

// messages go to the endpoint as a list of { role, content }; the newest 20 keep the answer on topic and the request small
async function ask() {
  const hist = db.all().slice().reverse().filter(m => m.role !== 'error').slice(-20);
  const last = hist.length ? hist[hist.length - 1].text : '';
  if (!cfg.endpoint) return 'Demo mode: no model is connected yet. Set "endpoint" in app.json to a server that calls the model (keep the API key on that server). You wrote: ' + last;
  const messages = [{ role: 'system', content: cfg.system || 'You are a helpful assistant.' }, ...hist.map(m => ({ role: m.role, content: m.text }))];
  const r = await fetch(cfg.endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ model: cfg.model || '', messages }) });
  if (!r.ok) throw new Error('the server answered HTTP ' + r.status);
  const j = await r.json();
  return j.reply || (j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content) || 'The server sent no reply.';
}

const log = h('div', { class: 'card', id: 'log', style: { height: '62vh', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '8px' } });
const textIn = h('textarea', { name: 'text', placeholder: 'Ask anything', rows: 2, style: { minHeight: '52px' }, onkeydown: e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); } } });
const sendBtn = h('button', { class: 'primary', id: 'send', onclick: () => submit() }, 'Send');
$('#main').replaceChildren(log, h('p', { class: 'muted small', style: { margin: '8px 2px' } }, cfg.endpoint ? 'Answers come from your model server. This chat stays on this device.' : 'Demo mode: connect a model by setting "endpoint" in app.json.'),
  h('div', { class: 'row', style: { alignItems: 'flex-end' } }, textIn, sendBtn));

function render() {
  const items = db.all().slice().reverse();
  const bubbles = items.map(m => h('div', { 'data-msg': m.role, class: 'card', style: { alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start', maxWidth: '86%', padding: '8px 12px', background: m.role === 'user' ? 'var(--accent)' : '', color: m.role === 'user' ? '#fff' : '', whiteSpace: 'pre-wrap' } }, m.text));
  if (ui.busy) bubbles.push(h('div', { 'data-msg': 'pending', class: 'muted', style: { alignSelf: 'flex-start' } }, 'Thinking…'));
  log.replaceChildren(...(bubbles.length ? bubbles : [h('div', { class: 'empty' }, cfg.greeting || 'Ask me anything to start.')]));
  log.scrollTop = log.scrollHeight;
  sendBtn.disabled = ui.busy;
}
async function submit() {
  const text = textIn.value.trim(); if (!text || ui.busy) return;
  db.add({ role: 'user', text }); textIn.value = ''; ui.busy = true; render();
  let reply;
  try { reply = await ask(); } catch (e) { db.add({ role: 'error', text: 'Could not get an answer: ' + e.message }); ui.busy = false; render(); return; }
  db.add({ role: 'assistant', text: reply }); ui.busy = false; render(); textIn.focus();
}
function clearChat() { confirmBox('Clear this conversation?', 'Clear').then(yes => { if (yes) { db.clear(); toast('Cleared'); } }); }

const auth = authButton(cfg, { h, modal, toast }); if (auth) $('#actions').append(auth);
$('#actions').append(h('button', { class: 'ghost', id: 'clear', title: 'New conversation', onclick: clearChat }, 'New'));
db.onChange = render; render();
window.__app = { cfg, db, items: () => db.all(), ask, render, ui, send: t => { textIn.value = t; return submit(); } };
