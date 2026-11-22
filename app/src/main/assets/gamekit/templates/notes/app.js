// Notes made from app.json: write, edit, pin and delete notes, tag them, search them, and read them formatted (# headings,
// **bold**, *italic*, lists, - [ ] check lists you can tick, links). Saved on the device; export all notes as Markdown.
// Play tests read window.__app. Part of PocketIDE's app kit; MIT licensed.
import { h, $, toast, modal, confirmBox, theme, loadJSON, download } from './appkit/dom.js';
import { openCollection, authButton } from './appkit/cloud.js';

const cfg = await loadJSON('app.json', {});
theme(cfg); $('#title').textContent = cfg.title || 'Notes';
const db = openCollection((cfg.title || 'notes').toLowerCase().replace(/\W+/g, '-'), cfg.sample || [], cfg);
const ui = { q: '', tag: '', open: null, editing: false };

const esc = s => s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
// a small, safe Markdown subset: the text is escaped first, so notes cannot inject HTML
function md(src, id) {
  let n = 0;
  return esc(src).split('\n').map(line => {
    let m;
    if ((m = /^(#{1,3}) (.*)$/.exec(line))) return `<h${m[1].length + 1}>${m[2]}</h${m[1].length + 1}>`;
    if ((m = /^- \[( |x)\] (.*)$/.exec(line))) { const k = n++; return `<label class="item"><input type="checkbox" data-check="${k}" ${m[1] === 'x' ? 'checked' : ''}> ${inline(m[2])}</label>`; }
    if ((m = /^[-*] (.*)$/.exec(line))) return `<li>${inline(m[1])}</li>`;
    return line.trim() ? `<p>${inline(line)}</p>` : '';
  }).join('');
}
const inline = s => s.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>').replace(/\*(.+?)\*/g, '<i>$1</i>').replace(/\[(.+?)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
function toggleCheck(note, k) { let i = 0; db.update(note.id, { body: note.body.split('\n').map(l => /^- \[( |x)\] /.test(l) ? (i++ === k ? l.replace(/^- \[( |x)\]/, (a, x) => x === 'x' ? '- [ ]' : '- [x]') : l) : l).join('\n') }); }

function editor(note) {
  const t = h('input', { id: 'note-title', placeholder: 'Title', value: note ? note.title : '' }), b = h('textarea', { id: 'note-body', placeholder: 'Write…', style: { minHeight: '45vh' } }, note ? note.body : '');
  const tags = new Set(note ? note.tags || [] : ui.tag ? [ui.tag] : []);
  return h('div', { class: 'card' }, h('label', { class: 'field' }, t), h('label', { class: 'field' }, b),
    h('div', { class: 'row', style: { marginBottom: '10px' } }, (cfg.tags || []).map(tg => h('button', { class: tags.has(tg) ? 'primary' : '', style: { minHeight: '32px', padding: '4px 10px' }, onclick: e => { tags.has(tg) ? tags.delete(tg) : tags.add(tg); e.target.className = tags.has(tg) ? 'primary' : ''; } }, tg))),
    h('div', { class: 'row' }, h('button', { onclick: () => { ui.editing = false; render(); } }, 'Cancel'),
      note ? h('button', { class: 'danger', onclick: async () => { if (await confirmBox('Delete this note?')) { db.remove(note.id); ui.open = null; ui.editing = false; toast('Deleted'); } } }, 'Delete') : null,
      h('button', { class: 'primary right', id: 'save', onclick: () => { const data = { title: t.value.trim() || 'Untitled', body: b.value, tags: [...tags] };
        if (note) db.update(note.id, data); else ui.open = db.add(data).id; ui.editing = false; toast('Saved'); render(); } }, 'Save')));
}
function render() {
  const main = $('#main');
  if (ui.editing) { main.replaceChildren(editor(ui.open ? db.get(ui.open) : null)); return; }
  const note = ui.open && db.get(ui.open);
  if (note) {
    const view = h('div', { class: 'card', id: 'note-view' }); view.innerHTML = md(note.body);
    view.addEventListener('change', e => { if (e.target.dataset.check != null) toggleCheck(note, +e.target.dataset.check); });
    main.replaceChildren(h('div', { class: 'row', style: { marginBottom: '10px' } }, h('button', { onclick: () => { ui.open = null; render(); } }, '‹ All notes'),
      h('button', { class: 'right', onclick: () => db.update(note.id, { pinned: !note.pinned }) }, note.pinned ? 'Unpin' : 'Pin'), h('button', { class: 'primary', id: 'edit', onclick: () => { ui.editing = true; render(); } }, 'Edit')),
      h('h2', {}, note.title), h('div', { class: 'row', style: { marginBottom: '8px' } }, (note.tags || []).map(t => h('span', { class: 'tag' }, t))), view);
    return;
  }
  const list = db.all().filter(n => (!ui.tag || (n.tags || []).includes(ui.tag)) && (!ui.q || (n.title + ' ' + n.body).toLowerCase().includes(ui.q.toLowerCase())))
    .sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || String(b.updated || b.created).localeCompare(String(a.updated || a.created)));
  main.replaceChildren(h('input', { type: 'search', id: 'search', placeholder: 'Search notes', value: ui.q, style: { marginBottom: '10px' }, oninput: e => { ui.q = e.target.value; render(); $('#search').focus(); } }),
    h('div', { class: 'tabs' }, ['', ...(cfg.tags || [])].map(t => h('button', { class: ui.tag === t ? 'on' : '', 'data-tag': t || 'all', onclick: () => { ui.tag = t; render(); } }, t || 'All'))),
    list.length ? h('div', { class: 'grid' }, list.map(n => h('div', { class: 'card', 'data-note': n.id, style: { cursor: 'pointer' }, onclick: () => { ui.open = n.id; render(); } }, h('div', { class: 'row' }, h('b', { class: 'grow' }, n.title), n.pinned ? '📌' : null),
      h('p', { class: 'muted small', style: { whiteSpace: 'pre-line', maxHeight: '4.5em', overflow: 'hidden' } }, n.body.replace(/[#*\[\]]/g, '').slice(0, 160)), h('div', { class: 'row' }, (n.tags || []).map(t => h('span', { class: 'tag' }, t)))))) : h('div', { class: 'empty' }, 'No notes yet.'),
    h('button', { class: 'primary fab', id: 'add', 'aria-label': 'New note', onclick: () => { ui.open = null; ui.editing = true; render(); } }, '+'));
}
{ const auth = authButton(cfg, { h, modal, toast }); if (auth) $('#actions').append(auth); }
$('#actions').append(h('button', { class: 'ghost', title: 'Export', onclick: () => download('notes.md', db.all().map(n => '# ' + n.title + '\n\n' + n.body).join('\n\n---\n\n'), 'text/markdown') }, '⤓'));
db.onChange = render; render();
window.__app = { cfg, db, ui, render, md };
