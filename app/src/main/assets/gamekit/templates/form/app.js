// Forms, quizzes and surveys made from app.json: an intro, steps of fields (text, email, number, date, textarea, select, choice,
// multi (check boxes), scale 1-5, yes/no), checks before moving on, a progress bar, and a result. mode "quiz" scores answers
// (field.answer) and explains them; "survey" and "form" collect answers. Every response is kept on the device and can be exported
// as CSV. Change the questions in app.json. Play tests read window.__app. Part of PocketIDE's app kit; MIT licensed.
import { h, $, toast, theme, loadJSON, download } from './appkit/dom.js';
import { Collection, toCSV } from './appkit/store.js';

const cfg = await loadJSON('app.json', {});
theme(cfg); $('#title').textContent = cfg.title || 'Form';
const steps = cfg.steps || [], quiz = cfg.mode === 'quiz', all = steps.flatMap(s => s.fields || []);
const db = new Collection((cfg.title || 'form').toLowerCase().replace(/\W+/g, '-') + '-responses');
const st = { step: cfg.intro ? -1 : 0, answers: {}, checked: {} };

function field(f) {
  const v = st.answers[f.id], set = val => { st.answers[f.id] = val; };
  const box = h('div', { class: 'field', 'data-field': f.id }, h('span', { style: { display: 'block', fontWeight: 600, marginBottom: '8px' } }, f.label + (f.required ? ' *' : '')));
  if (f.type === 'choice' || f.type === 'yesno') {
    const opts = f.type === 'yesno' ? ['Yes', 'No'] : f.options;
    box.append(h('div', { class: 'list card', style: { padding: '0 8px' } }, opts.map(o => h('label', { class: 'item', style: { cursor: 'pointer' } }, h('input', { type: 'radio', name: f.id, value: o, checked: v === o, disabled: quiz && st.checked[f.id], onchange: () => set(o) }), h('span', {}, o),
      quiz && st.checked[f.id] && o === f.answer ? h('span', { class: 'right', style: { color: 'var(--ok)' } }, '✓') : quiz && st.checked[f.id] && o === v ? h('span', { class: 'right', style: { color: 'var(--bad)' } }, '✗') : null))));
    if (quiz && st.checked[f.id]) box.append(h('p', { class: 'small', 'data-feedback': f.id, style: { color: v === f.answer ? 'var(--ok)' : 'var(--bad)' } }, (v === f.answer ? 'Right! ' : 'Not quite. ') + (f.explain || '')));
  } else if (f.type === 'multi') box.append(h('div', { class: 'list card', style: { padding: '0 8px' } }, f.options.map(o => h('label', { class: 'item' }, h('input', { type: 'checkbox', checked: (v || []).includes(o), onchange: e => set(e.target.checked ? [...(st.answers[f.id] || []), o] : (st.answers[f.id] || []).filter(x => x !== o)) }), o))));
  else if (f.type === 'scale') box.append(h('div', { class: 'row' }, [1, 2, 3, 4, 5].map(n => h('button', { type: 'button', class: v === n ? 'primary' : '', 'data-scale': n, onclick: () => { set(n); render(); } }, n)), h('span', { class: 'muted small' }, f.hint || '1 = not at all · 5 = very')));
  else if (f.type === 'select') box.append(h('select', { name: f.id, onchange: e => set(e.target.value) }, h('option', { value: '' }, 'Choose…'), f.options.map(o => h('option', { selected: v === o }, o))));
  else if (f.type === 'textarea') box.append(h('textarea', { name: f.id, oninput: e => set(e.target.value) }, v || ''));
  else box.append(h('input', { name: f.id, type: f.type || 'text', value: v ?? '', oninput: e => set(e.target.value) }));
  box.append(h('div', { class: 'err', id: 'err-' + f.id }));
  return box;
}
function problems(s) {
  let ok = true;
  for (const f of s.fields || []) {
    const v = st.answers[f.id], empty = v == null || v === '' || (Array.isArray(v) && !v.length);
    const msg = f.required && empty ? 'Please answer this' : f.type === 'email' && v && !/^\S+@\S+\.\S+$/.test(v) ? 'Please enter an email address' : quiz && f.answer != null && empty ? 'Pick an answer' : '';
    const e = $('#err-' + f.id); if (e) e.textContent = msg; if (msg) ok = false;
  }
  return ok;
}
const score = () => all.filter(f => f.answer != null && st.answers[f.id] === f.answer).length, max = () => all.filter(f => f.answer != null).length;
function next() {
  const s = steps[st.step]; if (s && !problems(s)) return;
  if (quiz && s) { const unchecked = (s.fields || []).filter(f => f.answer != null && !st.checked[f.id]); if (unchecked.length) { for (const f of unchecked) st.checked[f.id] = true; render(); return; } }
  st.step++;
  if (st.step >= steps.length) { db.add({ ...Object.fromEntries(Object.entries(st.answers).map(([k, v]) => [k, Array.isArray(v) ? v.join('; ') : v])), ...(quiz ? { score: score() } : {}) }); toast('Saved'); }
  render();
}
function render() {
  const main = $('#main');
  if (st.step < 0) { const i = cfg.intro; main.replaceChildren(h('div', { class: 'card', style: { textAlign: 'center', padding: '30px 16px' } }, h('h2', {}, i.title), h('p', { class: 'muted' }, i.text || ''), h('button', { class: 'primary', id: 'start', onclick: () => { st.step = 0; render(); } }, i.button || 'Start'))); return; }
  if (st.step >= steps.length) {
    const r = cfg.result || {}, sc = score(), band = (r.bands || []).filter(b => sc >= b.min).pop();
    main.replaceChildren(h('div', { class: 'card', id: 'result', style: { textAlign: 'center', padding: '30px 16px' } }, h('h2', {}, r.title || 'Thank you!'),
      quiz ? h('div', { class: 'kpi', id: 'score' }, sc + ' / ' + max()) : h('p', { class: 'muted' }, r.text || 'Your answers were saved.'), band ? h('p', {}, band.text) : null,
      h('div', { class: 'row', style: { justifyContent: 'center', marginTop: '12px' } }, h('button', { class: 'primary', onclick: () => { st.step = cfg.intro ? -1 : 0; st.answers = {}; st.checked = {}; render(); } }, quiz ? 'Play again' : 'New response'),
        h('button', { onclick: () => download('responses.csv', toCSV(db.all()), 'text/csv') }, 'Export ' + db.all().length + ' responses'))));
    return;
  }
  const s = steps[st.step], pct = Math.round(st.step / steps.length * 100);
  main.replaceChildren(h('div', { style: { height: '6px', background: 'var(--line)', borderRadius: '3px', marginBottom: '14px' } }, h('div', { id: 'progress', style: { width: pct + '%', height: '100%', background: 'var(--accent)', borderRadius: '3px' } })),
    h('div', { class: 'card' }, h('div', { class: 'muted small' }, 'Step ' + (st.step + 1) + ' of ' + steps.length), h('h2', {}, s.title || ''), s.text ? h('p', { class: 'muted' }, s.text) : null, (s.fields || []).map(field),
      h('div', { class: 'row', style: { marginTop: '8px' } }, st.step > 0 ? h('button', { onclick: () => { st.step--; render(); } }, 'Back') : null,
        h('button', { class: 'primary right', id: 'next', onclick: next }, quiz && (s.fields || []).some(f => f.answer != null && !st.checked[f.id]) ? 'Check' : st.step === steps.length - 1 ? 'Finish' : 'Next'))));
}
render();
window.__app = { cfg, st, db, score, render, next };
