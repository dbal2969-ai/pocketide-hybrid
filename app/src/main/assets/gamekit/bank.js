// Bank: tap a bank booth to open it. While it is open, tapping an item in the inventory deposits every one of that item;
// tapping an item in the bank takes out 1, 5, 10, X or All (chosen with the buttons at the top). Items keep the order they
// were first put in. The contents are part of the saved game. Written for PocketIDE's game kit; MIT licensed.

export class Bank {
  // o: { inv, say, iconDir, sfx }; mount(el) puts the panel into an element of the page.
  constructor(o) {
    Object.assign(this, o);
    this.iconDir = o.iconDir || 'data/icons';
    this.items = [];            // [{ id, count }] in the order they were first deposited
    this.mode = '1'; this.x = 10; this.node = null; this.onChange = () => {};
  }
  get isOpen() { return !!this.el && !this.el.hidden; }
  count(id) { const b = this.items.find(i => i.id === id); return b ? b.count : 0; }

  // ---- the contents ----
  put(id, n) { const b = this.items.find(i => i.id === id); if (b) b.count += n; else this.items.push({ id, count: n }); }
  // Moves every inventory item with the id of slot `i` into the bank; returns how many.
  deposit(i) {
    const s = this.inv.slots[i]; if (!s) return 0;
    const id = s.id; let n = 0;
    this.inv.slots.forEach((t, k) => { if (t && t.id === id) { n += t.count; this.inv.slots[k] = null; } });
    this.put(id, n); this.inv.onChange(); this.changed();
    this.say('You deposit ' + n + ' x ' + this.inv.name(id) + ' in the bank.');
    return n;
  }
  depositAll() {
    let n = 0;
    for (let i = 0; i < this.inv.slots.length; i++) if (this.inv.slots[i]) n += this.deposit(i);
    if (!n) this.say('You have nothing to deposit.');
  }
  // Takes up to `want` of an item out; as many as fit in the inventory.
  withdraw(id, want) {
    const have = this.count(id); if (!have) return 0;
    const n = Math.min(have, want);
    const left = this.inv.add(id, n), got = n - left;
    if (got <= 0) { this.say('You do not have enough inventory space.'); return 0; }
    const b = this.items.find(i => i.id === id); b.count -= got;
    if (b.count <= 0) this.items.splice(this.items.indexOf(b), 1);
    this.changed();
    this.say('You withdraw ' + got + ' x ' + this.inv.name(id) + '.');
    return got;
  }
  quantity() { return this.mode === 'all' ? Infinity : this.mode === 'x' ? Math.max(1, this.x | 0) : +this.mode; }
  changed() { this.render(); this.onChange(); }

  toJSON() { return this.items.map(i => ({ id: i.id, count: i.count })); }
  load(list) { if (Array.isArray(list)) this.items = list.filter(i => i && i.id >= 0 && i.count > 0).map(i => ({ id: i.id, count: i.count })); }

  // ---- the panel ----
  mount(el) {
    this.el = el; el.hidden = true;
    el.innerHTML = '<div class="bk-head"><b>Bank of Gielinor</b><span class="bk-x" title="Close">✕</span></div>' +
      '<div class="bk-row">Take out: <span class="bk-q" data-m="1">1</span><span class="bk-q" data-m="5">5</span><span class="bk-q" data-m="10">10</span>' +
      '<span class="bk-q" data-m="x">X</span><input class="bk-in" type="number" min="1" value="10" inputmode="numeric"><span class="bk-q" data-m="all">All</span></div>' +
      '<div class="bk-row"><span class="bk-btn" id="bk-dep">Deposit inventory</span><span class="bk-note">Tap an inventory item to deposit it.</span></div>' +
      '<div class="bk-grid"></div>';
    el.querySelector('.bk-x').addEventListener('pointerdown', e => { e.stopPropagation(); this.close(); });
    for (const b of el.querySelectorAll('.bk-q')) b.addEventListener('pointerdown', e => { e.stopPropagation(); this.mode = b.dataset.m; this.render(); });
    el.querySelector('.bk-in').addEventListener('input', e => { this.x = +e.target.value || 1; this.mode = 'x'; this.render(); });
    el.querySelector('#bk-dep').addEventListener('pointerdown', e => { e.stopPropagation(); this.depositAll(); });
    el.addEventListener('pointerdown', e => e.stopPropagation());
    this.render();
  }
  open(node) { if (!this.el) return; this.node = node || null; this.el.hidden = false; this.render(); this.say('You open the bank.'); }
  close() { if (this.el && !this.el.hidden) { this.el.hidden = true; this.node = null; } }
  render() {
    if (!this.el) return;
    for (const b of this.el.querySelectorAll('.bk-q')) b.classList.toggle('on', b.dataset.m === this.mode);
    const grid = this.el.querySelector('.bk-grid'); grid.innerHTML = '';
    if (!this.items.length) { const e = document.createElement('div'); e.className = 'bk-empty'; e.textContent = 'Your bank is empty.'; grid.appendChild(e); return; }
    for (const it of this.items) {
      const d = document.createElement('div'); d.className = 'slot bk-slot'; d.title = this.inv.name(it.id);
      const img = document.createElement('img'); img.src = this.iconDir + '/' + it.id + '.png'; img.alt = ''; img.draggable = false;
      img.onerror = () => { img.remove(); d.dataset.label = this.inv.name(it.id).slice(0, 5); };
      d.appendChild(img);
      const c = document.createElement('span'); c.className = 'cnt'; c.textContent = it.count > 99999 ? Math.floor(it.count / 1000) + 'K' : it.count; d.appendChild(c);
      d.addEventListener('pointerup', () => this.withdraw(it.id, this.quantity()));
      grid.appendChild(d);
    }
  }
}
