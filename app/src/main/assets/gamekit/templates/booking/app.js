// A booking calendar from app.json: pick a day and a service, see the free times, book one with a name and a contact, and
// cancel from the list of upcoming bookings. Saved on the device, or online when app.json has a cloud block (sign in to see yours).
// Play tests read window.__app. Part of PocketIDE's app kit; MIT licensed.
import { h, $, toast, modal, confirmBox, fmt, theme, loadJSON } from './appkit/dom.js';
import { openCollection, authButton } from './appkit/cloud.js';

const cfg = await loadJSON('app.json', {});
theme(cfg); $('#title').textContent = cfg.title || 'Bookings';
const services = cfg.services || [{ name: 'Appointment', minutes: 60, price: 0 }];
const toMin = s => { const [h, m] = String(s).split(':').map(Number); return h * 60 + (m || 0); };
const hm = m => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
const OPEN = toMin(cfg.open || '09:00'), CLOSE = toMin(cfg.close || '17:00'), STEP = cfg.step || 30;
const OPEN_DAYS = cfg.days || [1, 2, 3, 4, 5];   // 0 = Sunday ... 6 = Saturday
const db = openCollection(cfg.storeName || 'bookings', cfg.sample || [], cfg);   // online when app.json has a cloud block
const ui = { day: localToday(), service: services[0].name };

function localToday(d = new Date()) { return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); }
const svc = name => services.find(s => s.name === name) || services[0];
const isOpenDay = day => OPEN_DAYS.includes(new Date(day + 'T00:00').getDay());
const active = () => db.all().filter(b => b.status !== 'cancelled');
const overlaps = (a, alen, b, blen) => a < b + blen && b < a + alen;

// the times a service can start on a day: free ones are bookable, the rest are shown but disabled
function slotList(day, s) {
  const now = new Date(), nowMin = now.getHours() * 60 + now.getMinutes(), out = [];
  for (let t = OPEN; t + s.minutes <= CLOSE; t += STEP) {
    const busy = active().some(b => b.day === day && overlaps(t, s.minutes, toMin(b.start), +b.minutes || 60));
    const past = day === localToday() && t <= nowMin;
    out.push({ start: hm(t), free: !busy && !past });
  }
  return out;
}

function field(name, label, required, kind = 'input') {
  return h('label', { class: 'field' }, h('span', {}, label + (required ? ' *' : '')),
    kind === 'textarea' ? h('textarea', { name }) : h('input', { name, autocomplete: name === 'contact' ? 'email' : 'name' }), h('div', { class: 'err', id: 'err-' + name }));
}
function bookForm(day, start, s) {
  const form = h('form', { onsubmit: e => e.preventDefault() },
    h('p', { class: 'muted' }, `${s.name} · ${fmt.date(day)} at ${start} · ${s.minutes} min` + (s.price ? ' · ' + fmt.money(s.price, cfg.currency) : '')),
    field('name', 'Your name', true), field('contact', 'Email or phone', true), field('notes', 'Notes (optional)', false, 'textarea'));
  const save = () => {
    const v = n => form.querySelector(`[name="${n}"]`).value.trim();
    let ok = true;
    for (const n of ['name', 'contact']) { const bad = !v(n); form.querySelector('#err-' + n).textContent = bad ? 'Please fill this in' : ''; if (bad) ok = false; }
    if (!ok) return false;
    if (active().some(b => b.day === day && b.start === start)) { toast('That time was just booked'); return false; }
    db.add({ day, start, minutes: s.minutes, service: s.name, name: v('name'), contact: v('contact'), notes: v('notes'), status: 'booked' });
    toast('Booked ' + start);
  };
  modal({ title: 'Book a time', body: form, actions: [{ label: 'Cancel' }, { label: 'Book', primary: true, onClick: save }] });
}
function cancel(b) {
  confirmBox('Cancel this booking?', 'Cancel booking').then(yes => { if (yes) { db.update(b.id, { status: 'cancelled' }); toast('Cancelled'); } });
}

function render() {
  const s = svc(ui.service), main = $('#main');
  const open = isOpenDay(ui.day), slots = open ? slotList(ui.day, s) : [];
  const upcoming = active().filter(b => b.day >= localToday()).sort((a, b) => (a.day + a.start) < (b.day + b.start) ? -1 : 1);
  main.replaceChildren(
    h('div', { class: 'card', style: { marginBottom: '14px' } },
      h('div', { class: 'row' },
        h('label', { class: 'grow' }, h('span', { class: 'small muted' }, 'Day'),
          h('input', { type: 'date', name: 'day', value: ui.day, min: localToday(), onchange: e => { if (e.target.value) { ui.day = e.target.value; render(); } }, oninput: e => { if (e.target.value) { ui.day = e.target.value; render(); } } })),
        h('label', { class: 'grow' }, h('span', { class: 'small muted' }, 'Service'),
          h('select', { name: 'service', onchange: e => { ui.service = e.target.value; render(); } },
            services.map(x => h('option', { value: x.name, selected: x.name === ui.service }, `${x.name} (${x.minutes} min)`))))),
      h('h2', { style: { marginTop: '14px' } }, fmt.date(ui.day)),
      !open ? h('div', { class: 'empty' }, 'Closed on this day. Pick another day.')
        : slots.length ? h('div', { class: 'grid', style: { gridTemplateColumns: 'repeat(auto-fill,minmax(92px,1fr))' } },
          slots.map(t => h('button', { 'data-slot': t.start, disabled: !t.free, class: t.free ? '' : 'ghost', onclick: () => bookForm(ui.day, t.start, s) }, t.start)))
        : h('div', { class: 'empty' }, 'No times fit this service on this day.')),
    h('h2', {}, 'Upcoming'),
    upcoming.length ? h('div', { class: 'card list', style: { padding: '0 10px' } }, upcoming.map(b => h('div', { class: 'item', 'data-booking': b.id },
      h('div', { class: 'grow' }, h('div', { style: { fontWeight: 600 } }, `${fmt.date(b.day)} ${b.start} · ${b.name}`),
        h('div', { class: 'muted small' }, `${b.service} · ${b.contact}` + (b.notes ? ' · ' + b.notes : ''))),
      h('button', { class: 'ghost danger', onclick: () => cancel(b) }, 'Cancel'))))
      : h('div', { class: 'empty' }, 'No upcoming bookings.'));
}

const auth = authButton(cfg, { h, modal, toast }); if (auth) $('#actions').append(auth);
db.onChange = render; render();
window.__app = { cfg, db, render, items: () => db.all(), slots: () => slotList(ui.day, svc(ui.service)), ui };
