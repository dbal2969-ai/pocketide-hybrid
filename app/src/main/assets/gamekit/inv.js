// Inventory of 28 slots. Tap a slot to select it, long press for Use / Drop / Examine, drag one slot onto another to swap.
// Coins and other stackable items show their count. set onTap(i) to take over a tap (the bank uses it); changed() is called after every change. MIT licensed.

export class Inventory {
  constructor(size = 28, stackable = [995]) { this.slots = Array(size).fill(null); this.stackable = new Set(stackable); this.names = {}; this.onChange = () => {}; this.changed = () => {}; this.onTap = null; this.selected = -1; }

  setNames(list) { if (list) for (const it of list) { if (it.name) this.names[it.id] = it.name; if (it.stackable) this.stackable.add(it.id); } }
  name(id) { return this.names[id] || 'item ' + id; }

  // Adds count of an item; returns how many did not fit (the inventory was full).
  add(id, count = 1) {
    if (this.stackable.has(id)) {
      const s = this.slots.find(x => x && x.id === id);
      if (s) { s.count += count; this.onChange(); return 0; }
      const i = this.slots.indexOf(null); if (i < 0) return count;
      this.slots[i] = { id, count }; this.onChange(); return 0;
    }
    let left = count;
    while (left > 0) { const i = this.slots.indexOf(null); if (i < 0) break; this.slots[i] = { id, count: 1 }; left--; }
    this.onChange(); return left;
  }
  remove(i, count = 1) { const s = this.slots[i]; if (!s) return null; const n = Math.min(count, s.count); s.count -= n; if (s.count <= 0) this.slots[i] = null; this.onChange(); return { id: s.id, count: n }; }
  swap(a, b) { [this.slots[a], this.slots[b]] = [this.slots[b], this.slots[a]]; this.onChange(); }
  count(id) { return this.slots.reduce((n, s) => n + (s && s.id === id ? s.count : 0), 0); }

  save(key = 'game.inventory:' + location.pathname) { try { localStorage.setItem(key, JSON.stringify(this.slots)); } catch (e) { /* storage blocked */ } }
  load(key = 'game.inventory:' + location.pathname) { try { const t = JSON.parse(localStorage.getItem(key) || 'null'); if (Array.isArray(t) && t.length === this.slots.length) { this.slots = t; return true; } } catch (e) { /* none saved */ } return false; }

  // Draws the slots into `root` (an element) and wires tap, long press and drag. say(text) shows a chat message.
  mount(root, say, iconDir = 'data/icons') {
    this.root = root; this.say = say; this.iconDir = iconDir;
    this.onChange = () => { this.render(); this.changed(); };   // changed() is set by the game: it saves everything together
    this.render();
  }
  render() {
    const root = this.root; if (!root) return;
    root.innerHTML = '';
    this.slots.forEach((s, i) => {
      const d = document.createElement('div'); d.className = 'slot' + (i === this.selected ? ' sel' : ''); d.dataset.i = i;
      if (s) {
        const img = document.createElement('img'); img.src = this.iconDir + '/' + s.id + '.png'; img.alt = ''; img.draggable = false;
        img.onerror = () => { img.remove(); d.dataset.label = this.name(s.id).slice(0, 5); };
        d.appendChild(img);
        if (s.count > 1) { const c = document.createElement('span'); c.className = 'cnt'; c.textContent = s.count > 99999 ? Math.floor(s.count / 1000) + 'K' : s.count; d.appendChild(c); }
      }
      this.wire(d, i); root.appendChild(d);
    });
  }
  wire(d, i) {
    let timer = null, down = null, moved = false, longed = false;
    d.addEventListener('pointerdown', e => {
      down = { x: e.clientX, y: e.clientY }; moved = false; longed = false;
      timer = setTimeout(() => { timer = null; if (!moved && this.slots[i]) { longed = true; this.menu(i, e.clientX, e.clientY); } }, 500);
    });
    d.addEventListener('pointermove', e => { if (down && Math.hypot(e.clientX - down.x, e.clientY - down.y) > 8) { moved = true; clearTimeout(timer); timer = null; } });
    d.addEventListener('pointerup', e => {
      clearTimeout(timer); const was = !longed && !moved; timer = null;
      if (moved) {   // drag: swap with the slot under the finger
        const over = document.elementFromPoint(e.clientX, e.clientY), t = over && over.closest ? over.closest('.slot') : null;
        if (t && t.dataset.i !== undefined && +t.dataset.i !== i) this.swap(i, +t.dataset.i);
      } else if (was && this.slots[i]) { if (this.onTap && this.onTap(i)) { down = null; return; } this.selected = this.selected === i ? -1 : i; this.render(); if (this.selected >= 0) this.say('You select the ' + this.name(this.slots[i].id) + '.'); }
      down = null;
    });
  }
  menu(i, x, y) {
    const old = document.getElementById('invmenu'); if (old) old.remove();
    const m = document.createElement('div'); m.id = 'invmenu';
    m.style.cssText = 'position:fixed;z-index:9;background:#3b3024;border:2px solid #120c04;color:#ffd;font:13px sans-serif;left:' + Math.max(4, x - 70) + 'px;top:' + Math.max(4, y - 90) + 'px';
    const s = this.slots[i], nm = this.name(s.id);
    // extraActions(i): the game's own options for this item first (Eat, Bury, ... from content.js), as [label, fn]
    for (const [label, fn] of [...(this.extraActions ? this.extraActions(i) : []), ['Use ' + nm, () => { this.selected = i; this.render(); this.say('You use the ' + nm + '. Tap what to use it on.'); }],
      ['Drop ' + nm, () => { this.remove(i, s.count); this.say('You drop the ' + nm + '.'); }],
      ['Examine ' + nm, () => this.say(s.count > 1 ? s.count + ' x ' + nm + '.' : 'It is a ' + nm + '.')]]) {
      const b = document.createElement('div'); b.textContent = label; b.style.cssText = 'padding:6px 10px;border-bottom:1px solid #120c04';
      b.addEventListener('pointerdown', ev => { ev.stopPropagation(); fn(); m.remove(); }); m.appendChild(b);
    }
    document.body.appendChild(m);
    setTimeout(() => addEventListener('pointerdown', () => m.remove(), { once: true }), 0);
  }
}
