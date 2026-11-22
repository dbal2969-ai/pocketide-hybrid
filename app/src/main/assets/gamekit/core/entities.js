// Entities: plain objects with an id, kept in a list with simple queries and removal at a safe time.
//   const world = new Entities(); const e = world.add({ kind: 'coin', x: 10, y: 20, w: 8, h: 8 });
//   world.each(e => ...); world.kind('coin'); world.remove(e); world.sweep();   (sweep once per frame after update)
// Part of PocketIDE's game kit; MIT licensed.
export class Entities {
  constructor() { this.list = []; this.nextId = 1; }

  add(props) { const e = Object.assign({ id: this.nextId++, alive: true }, props); this.list.push(e); return e; }
  remove(e) { e.alive = false; }
  sweep() { if (this.list.some(e => !e.alive)) this.list = this.list.filter(e => e.alive); }
  each(fn) { for (const e of this.list.slice()) if (e.alive) fn(e); }
  kind(k) { return this.list.filter(e => e.alive && e.kind === k); }
  find(fn) { return this.list.find(e => e.alive && fn(e)); }
  count(k) { return this.list.reduce((n, e) => n + (e.alive && (!k || e.kind === k) ? 1 : 0), 0); }
  clear() { this.list = []; }
  nearest(x, y, k, maxDist = Infinity) {
    let best = null, bd = maxDist;
    for (const e of this.list) { if (!e.alive || (k && e.kind !== k)) continue; const d = Math.hypot(e.x - x, e.y - y); if (d < bd) { bd = d; best = e; } }
    return best;
  }
}
