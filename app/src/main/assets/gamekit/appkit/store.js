// Saving app data on the device: a versioned key-value store (local storage, with a memory fallback when storage is blocked)
// and a collection of records with add / update / remove, change events and CSV / JSON export and import.
//   const tasks = new Collection('tasks', sampleRows); tasks.add({ title: 'Buy milk' }); tasks.all(); tasks.onChange = render;
// Part of PocketIDE's app kit; MIT licensed.
const memory = {};
export const Store = {
  get(key, fallback = null) { let t = null; try { t = localStorage.getItem('app:' + key); } catch (e) { t = memory[key] ?? null; } if (t == null) t = memory[key] ?? null; if (t == null) return fallback; try { return JSON.parse(t); } catch (e) { return fallback; } },
  set(key, value) { const t = JSON.stringify(value); memory[key] = t; try { localStorage.setItem('app:' + key, t); return true; } catch (e) { return false; } },
  remove(key) { delete memory[key]; try { localStorage.removeItem('app:' + key); } catch (e) { /* blocked */ } }
};

export class Collection {
  // sample: records to start with the first time (each gets an id); they stay until the person changes them
  constructor(name, sample = []) {
    this.name = name; this.onChange = () => {};
    const saved = Store.get('c:' + name, null);
    this.items = Array.isArray(saved) ? saved : sample.map((r, i) => ({ id: r.id || 's' + i, ...r }));
  }
  save() { Store.set('c:' + this.name, this.items); this.onChange(this.items); }
  all() { return this.items.slice(); }
  get(id) { return this.items.find(r => r.id === id) || null; }
  add(rec) { const r = { id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6), created: new Date().toISOString(), ...rec }; this.items.unshift(r); this.save(); return r; }
  update(id, patch) { const r = this.get(id); if (!r) return null; Object.assign(r, patch, { updated: new Date().toISOString() }); this.save(); return r; }
  remove(id) { const n = this.items.length; this.items = this.items.filter(r => r.id !== id); if (this.items.length !== n) this.save(); }
  replace(rows) { this.items = rows.map((r, i) => ({ id: r.id || 'i' + i + Date.now().toString(36), ...r })); this.save(); }
  clear() { this.items = []; this.save(); }
}

// rows to CSV text (fields: column names; values with commas, quotes or new lines are quoted)
export function toCSV(rows, fields) {
  const cols = fields || [...new Set(rows.flatMap(r => Object.keys(r)))];
  const q = v => { const s = v == null ? '' : Array.isArray(v) ? v.join('; ') : String(v); return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
  return [cols.join(','), ...rows.map(r => cols.map(c => q(r[c])).join(','))].join('\n');
}
// CSV text to rows (first line: column names); numbers stay text unless asked: fromCSV(t, { numbers: true })
export function fromCSV(text, { numbers = false } = {}) {
  const rows = []; let row = [], cell = '', q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) { if (c === '"' && text[i + 1] === '"') { cell += '"'; i++; } else if (c === '"') q = false; else cell += c; continue; }
    if (c === '"') q = true; else if (c === ',') { row.push(cell); cell = ''; } else if (c === '\n' || c === '\r') { if (c === '\r' && text[i + 1] === '\n') i++; row.push(cell); rows.push(row); row = []; cell = ''; } else cell += c;
  }
  if (cell !== '' || row.length) { row.push(cell); rows.push(row); }
  const head = (rows.shift() || []).map(s => s.trim());
  return rows.filter(r => r.some(v => v !== '')).map(r => Object.fromEntries(head.map((k, i) => [k, numbers && r[i] !== '' && !isNaN(+r[i]) ? +r[i] : r[i] ?? ''])));
}
