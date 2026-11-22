// Sign-in and an online database for any app kit, through Supabase's own web API (no library). Off until app.json has
//   "cloud": { "provider": "supabase", "url": "https://YOUR-PROJECT.supabase.co", "anonKey": "YOUR-ANON-KEY", "table": "app_items" }
// Then people sign in with email and password, and every collection is kept in the table, private to each person (cloud-setup.sql
// turns on row level security so nobody can read another person's rows; collections named "shared:..." are readable by everyone
// signed in, for chat rooms). Without the cloud block, or before signing in, the data stays on the device as before.
//   const db = openCollection('tasks', sample, cfg);   // same API as Collection: all, get, add, update, remove, replace, onChange
//   header.append(authButton(cfg));                   // "Sign in" / the person's email, with a sign-in and sign-up dialog
// The anon key is meant to be public; the security comes from the policies in cloud-setup.sql. Never put a service key in an app.
// Part of PocketIDE's app kit; MIT licensed.
import { Store, Collection } from './store.js';

const SESSION = 'cloud:session';

export class Cloud {
  constructor(conf = {}) { this.url = String(conf.url || '').replace(/\/+$/, ''); this.key = conf.anonKey || ''; this.table = conf.table || 'app_items'; this.session = Store.get(SESSION, null); this.listeners = new Set(); }
  get ready() { return !!(this.url && this.key); }
  get user() { return this.session && this.session.user || null; }
  onChange(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  emit() { for (const f of this.listeners) try { f(this.user); } catch (e) { /* a listener failed */ } }
  headers(auth = true) { const h = { apikey: this.key, 'Content-Type': 'application/json' }; if (auth && this.session) h.Authorization = 'Bearer ' + this.session.access_token; return h; }
  async call(path, opts = {}) {
    const r = await fetch(this.url + path, { ...opts, headers: { ...this.headers(opts.auth !== false), ...(opts.headers || {}) } });
    const text = await r.text(); let body = null; try { body = text ? JSON.parse(text) : null; } catch (e) { body = text; }
    if (!r.ok) throw new Error((body && (body.error_description || body.msg || body.message || body.error)) || ('HTTP ' + r.status));
    return body;
  }
  keep(s) { if (!s || !s.access_token) throw new Error('No session came back. If sign-up needs email confirmation, confirm and then sign in.'); this.session = { ...s, expires_at: Date.now() + (s.expires_in || 3600) * 1000 }; Store.set(SESSION, this.session); this.emit(); return this.user; }
  async signUp(email, password) { const s = await this.call('/auth/v1/signup', { method: 'POST', auth: false, body: JSON.stringify({ email, password }) }); return s.access_token ? this.keep(s) : null; }
  async signIn(email, password) { return this.keep(await this.call('/auth/v1/token?grant_type=password', { method: 'POST', auth: false, body: JSON.stringify({ email, password }) })); }
  signOut() { this.session = null; Store.remove(SESSION); this.emit(); }
  async fresh() {   // renews the session a minute before it runs out
    if (!this.session) return false;
    if (Date.now() < (this.session.expires_at || 0) - 60000) return true;
    try { this.keep(await this.call('/auth/v1/token?grant_type=refresh_token', { method: 'POST', auth: false, body: JSON.stringify({ refresh_token: this.session.refresh_token }) })); return true; } catch (e) { this.signOut(); return false; }
  }
  // rows of one collection: [{ id, collection, data, owner, updated_at }]
  async rows(collection) { await this.fresh(); return this.call(`/rest/v1/${this.table}?collection=eq.${encodeURIComponent(collection)}&select=*&order=updated_at.desc`); }
  async put(collection, records) { await this.fresh(); return this.call(`/rest/v1/${this.table}`, { method: 'POST', headers: { Prefer: 'resolution=merge-duplicates,return=minimal' }, body: JSON.stringify(records.map(r => ({ id: r.id, collection, data: r, updated_at: new Date().toISOString() }))) }); }
  async del(id) { await this.fresh(); return this.call(`/rest/v1/${this.table}?id=eq.${encodeURIComponent(id)}`, { method: 'DELETE' }); }
}

const clouds = new Map();
export function cloudFor(cfg = {}) { const c = cfg.cloud; if (!c || !c.url || !c.anonKey) return null; const k = c.url + '|' + c.anonKey; if (!clouds.has(k)) clouds.set(k, new Cloud(c)); return clouds.get(k); }

// A Collection whose records also live in the online table once someone is signed in. Changes show at once and are sent in the
// background; problems are reported through onError. pull() reloads from the table (it runs on sign-in and every `poll` ms when set).
export class CloudCollection extends Collection {
  constructor(name, sample, cloud, { poll = 0 } = {}) {
    super(name + '@' + (cloud.user ? cloud.user.id : 'device'), sample);
    this.base = name; this.cloud = cloud; this.onError = e => console.warn('cloud: ' + e.message); this.pending = Promise.resolve(); this.dirty = new Set(); this.merged = false;
    cloud.onChange(u => { if (!u) this.merged = false; this.pull(); });
    if (cloud.user) this.pull();
    if (poll) setInterval(() => { if (this.cloud.user) this.pull(); }, poll);
  }
  get online() { return !!this.cloud.user; }
  // a change is sent in the background; until the server has it, its id stays in `dirty` so a reload cannot drop it
  send(ids, fn) { if (!this.online) return; for (const id of ids) this.dirty.add(id); this.pending = this.pending.then(fn).then(() => { for (const id of ids) this.dirty.delete(id); }).catch(e => this.onError(e)); }
  async pull() {
    if (!this.online) return;
    try {
      const rows = await this.cloud.rows(this.base), remote = new Map(rows.map(r => [r.id, { ...r.data, id: r.id }]));
      // the first time after signing in, records made on this device before are added to the account; after that only unsent changes are kept
      const keep = this.items.filter(r => !remote.has(r.id) && (!this.merged || this.dirty.has(r.id)));
      for (const r of this.items) if (remote.has(r.id) && this.dirty.has(r.id)) remote.set(r.id, r);   // our newer unsent edit wins
      this.items = [...keep, ...remote.values()];
      if (!this.merged && keep.length) this.send(keep.map(r => r.id), () => this.cloud.put(this.base, keep));
      this.merged = true;
      Store.set('c:' + this.name, this.items); this.onChange(this.items);
    } catch (e) { this.onError(e); }
  }
  add(rec) { const r = super.add(rec); this.send([r.id], () => this.cloud.put(this.base, [r])); return r; }
  update(id, patch) { const r = super.update(id, patch); if (r) this.send([id], () => this.cloud.put(this.base, [r])); return r; }
  remove(id) { super.remove(id); this.dirty.delete(id); this.send([], () => this.cloud.del(id)); }
  replace(rows) { super.replace(rows); this.send(this.items.map(r => r.id), () => this.cloud.put(this.base, this.items)); }
}

// The collection an app should use: online when app.json has a cloud block, on the device otherwise.
export function openCollection(name, sample = [], cfg = {}, opts = {}) { const c = cloudFor(cfg); return c ? new CloudCollection(name, sample, c, opts) : new Collection(name, sample); }

// A header button for signing in and out (nothing when the app has no cloud block). h: the h() helper from dom.js.
export function authButton(cfg, { h, modal, toast }) {
  const cloud = cloudFor(cfg); if (!cloud) return null;
  const btn = h('button', { class: 'ghost', id: 'auth' });
  const label = () => { btn.textContent = cloud.user ? '👤 ' + String(cloud.user.email || 'Signed in').split('@')[0] : 'Sign in'; };
  cloud.onChange(label); label();
  btn.addEventListener('click', () => {
    if (cloud.user) { modal({ title: cloud.user.email || 'Signed in', body: h('p', { class: 'muted' }, 'Your data is saved online and on this device.'), actions: [{ label: 'Close' }, { label: 'Sign out', danger: true, onClick: () => { cloud.signOut(); toast('Signed out'); } }] }); return; }
    const email = h('input', { type: 'email', name: 'email', placeholder: 'Email', autocomplete: 'email' }), pw = h('input', { type: 'password', name: 'password', placeholder: 'Password (6+ characters)', autocomplete: 'current-password' }), err = h('div', { class: 'err' });
    const go = up => { err.textContent = ''; (up ? cloud.signUp(email.value.trim(), pw.value) : cloud.signIn(email.value.trim(), pw.value)).then(u => { if (u) { toast('Signed in'); close(); } else err.textContent = 'Check your email to confirm the account, then sign in.'; }).catch(e => { err.textContent = e.message; }); return false; };
    const close = modal({ title: 'Sign in', body: h('div', {}, h('label', { class: 'field' }, email), h('label', { class: 'field' }, pw), err), actions: [{ label: 'Create account', onClick: () => go(true) }, { label: 'Sign in', primary: true, onClick: () => go(false) }] });
  });
  return btn;
}

// The SQL that makes the table and its privacy rules (paste it once in Supabase: SQL editor). Also written as cloud-setup.sql.
export const SETUP_SQL = `create table if not exists app_items (
  id text primary key,
  owner uuid not null default auth.uid() references auth.users on delete cascade,
  collection text not null,
  data jsonb not null,
  updated_at timestamptz not null default now()
);
alter table app_items enable row level security;
create policy "own rows" on app_items for all using (auth.uid() = owner) with check (auth.uid() = owner);
create policy "shared rows readable when signed in" on app_items for select using (collection like 'shared:%' and auth.role() = 'authenticated');
create policy "signed-in people can post shared rows" on app_items for insert with check (collection like 'shared:%' and auth.uid() = owner);`;
