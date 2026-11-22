// Gathering: trees you can chop, found in the map by the "Chop down" action of their own object definition, and bank booths
// (the "Bank" action). The numbers (levels, experience, how often a swing works, how fast trees grow back) are the ones in the
// rsmod woodcutting files; how long a tree stands while you cut it is simplified to a number of ticks.
// To add mining or fishing, copy this: a table of what can be gathered, a success formula, and the same tick() loop.
// Written for PocketIDE's game kit; MIT licensed.

// Axes you can carry, best first: [item id, level needed].
export const AXES = [[6739, 61], [1359, 41], [1357, 31], [1355, 21], [1361, 11], [1353, 6], [1349, 1], [1351, 1]];
const AXE_KEY = { 6739: 'dragon_axe', 1359: 'rune_axe', 1357: 'adamant_axe', 1355: 'mithril_axe', 1361: 'black_axe', 1353: 'steel_axe', 1349: 'iron_axe', 1351: 'bronze_axe' };

// [low, high] per axe for each kind of tree (rsmod: the chance of a swing working rises from low at level 1 to high at level 99).
const RATES = {
  regular: { bronze_axe: [64, 200], iron_axe: [96, 300], steel_axe: [128, 400], black_axe: [144, 450], mithril_axe: [160, 500], adamant_axe: [192, 600], rune_axe: [224, 700], dragon_axe: [240, 750] },
  oak: { bronze_axe: [32, 100], iron_axe: [48, 150], steel_axe: [64, 200], black_axe: [72, 225], mithril_axe: [80, 250], adamant_axe: [96, 300], rune_axe: [112, 350], dragon_axe: [120, 375] },
  willow: { bronze_axe: [16, 50], iron_axe: [24, 75], steel_axe: [32, 100], black_axe: [36, 112], mithril_axe: [40, 125], adamant_axe: [48, 150], rune_axe: [56, 175], dragon_axe: [60, 187] },
  maple: { bronze_axe: [8, 25], iron_axe: [12, 37], steel_axe: [16, 50], black_axe: [18, 56], mithril_axe: [20, 62], adamant_axe: [24, 75], rune_axe: [28, 87], dragon_axe: [30, 93] },
  yew: { bronze_axe: [4, 12], iron_axe: [6, 19], steel_axe: [8, 25], black_axe: [9, 28], mithril_axe: [10, 31], adamant_axe: [12, 37], rune_axe: [14, 44], dragon_axe: [15, 47] },
  magic: { bronze_axe: [2, 6], iron_axe: [3, 9], steel_axe: [4, 12], black_axe: [5, 13], mithril_axe: [5, 15], adamant_axe: [6, 18], rune_axe: [7, 21], dragon_axe: [7, 22] }
};

// level and experience to chop, the logs you get, how many ticks of cutting before it falls (0 = one log, then it falls) and how many ticks it takes to grow back [min, max].
export const TREES = {
  regular: { name: 'tree', level: 1, xp: 25, log: 1511, fall: 0, back: [60, 100] },
  oak: { name: 'oak tree', level: 15, xp: 37.5, log: 1521, fall: 45, back: [15, 15] },
  willow: { name: 'willow tree', level: 30, xp: 67.5, log: 1519, fall: 50, back: [15, 15] },
  maple: { name: 'maple tree', level: 45, xp: 100, log: 1517, fall: 100, back: [60, 60] },
  yew: { name: 'yew tree', level: 60, xp: 175, log: 1515, fall: 190, back: [100, 100] },
  magic: { name: 'magic tree', level: 75, xp: 250, log: 1513, fall: 390, back: [200, 200] }
};

export function treeKind(name) {
  const n = String(name || '').toLowerCase();
  for (const k of ['oak', 'willow', 'maple', 'yew', 'magic']) if (n.startsWith(k)) return k;
  return 'regular';
}

// The chance that one swing works, as the game works it out.
export function successRate(low, high, level) { return (1 + Math.floor(low * (99 - level) / 98 + high * (level - 1) / 98 + 0.5)) / 256; }

// Finds every tree and bank booth on plane 0 of the loaded squares. defs: objects.json as { id: definition }.
// A node: { key, kind: 'tree' | 'bank', type, name, x, z, w, l, rx, rz, lkey } (x, z the south-west tile, w by l tiles).
export function indexNodes(world, defs) {
  const nodes = [], at = new Map();
  for (const sq of world.squares.values()) {
    const rx = sq.json.x, rz = sq.json.z;
    for (const o of sq.json.objects || []) {
      if (o[3] !== 0) continue;
      const d = defs[o[0]]; if (!d || !d.actions) continue;
      const acts = d.actions.map(a => String(a || '').toLowerCase());
      // Old School booths say "Bank"; 2009-era ones say "Use" (and "Use-quickly"), so the name decides there
      const tree = acts.includes('chop down'), bank = acts.includes('bank') || (/^bank (booth|chest)$/i.test(d.name || '') && acts.includes('use'));
      if (!tree && !bank) continue;
      const turned = o[5] & 1, w = (turned ? d.length : d.width) || 1, l = (turned ? d.width : d.length) || 1;
      const x = rx * 64 + o[1], z = rz * 64 + o[2];
      const n = { key: x + ',' + z, kind: tree ? 'tree' : 'bank', type: tree ? treeKind(d.name) : 'bank', name: d.name || '', x, z, w, l, rx, rz, lkey: o.slice(0, 4).join(','), down: false };
      nodes.push(n);
      for (let i = 0; i < w; i++) for (let j = 0; j < l; j++) at.set((x + i) + ',' + (z + j), n);
    }
  }
  return { nodes, at };
}

// true when the player stands next to the node (or on it)
export function touches(me, n) {
  const dx = Math.max(n.x - me.x, 0, me.x - (n.x + n.w - 1)), dz = Math.max(n.z - me.z, 0, me.z - (n.z + n.l - 1));
  return Math.max(dx, dz) <= 1;
}

export class Gathering {
  // o: { index (from indexNodes), skills, inv, say, view (hide(node) / show(node)), sfx, rand (a function returning 0..1) }
  constructor(o) {
    Object.assign(this, o);
    this.rand = o.rand || Math.random;
    this.active = null; this.now = 0; this.felled = [];
  }
  nodeAt(x, z) { return this.index.at.get(x + ',' + z) || null; }

  // The best axe in the inventory that the player's level can use: [item id, level] or null.
  axe() {
    const lvl = this.skills.level('woodcutting');
    return AXES.find(([id, need]) => need <= lvl && this.inv.count(id) > 0) || null;
  }

  start(n) {
    if (n.kind !== 'tree') return;
    this.active = { node: n, t: 0, cut: 0 };
  }
  stop() { this.active = null; }

  // One game tick. Returns 'walk' when the player must come closer, 'busy' while chopping, or '' when idle.
  tick(me) {
    this.now++;
    for (let i = this.felled.length - 1; i >= 0; i--) {
      const f = this.felled[i];
      if (this.now >= f.back) { f.node.down = false; this.view.show(f.node); this.felled.splice(i, 1); }
    }
    const a = this.active; if (!a) return '';
    const n = a.node, t = TREES[n.type];
    if (n.down) { this.say('The tree has already been felled.'); this.stop(); return ''; }
    if (!touches(me, n)) return 'walk';
    if (a.t === 0) {   // the checks before the first swing
      if (this.skills.level('woodcutting') < t.level) { this.say('You need a Woodcutting level of ' + t.level + ' to chop down this tree.'); this.stop(); return ''; }
      if (!this.axe()) { this.say('You need an axe to chop down this tree.'); this.say('You do not have an axe which you have the woodcutting level to use.'); this.stop(); return ''; }
      this.say('You swing your axe at the tree.');
    }
    a.t++;
    if (a.t % 4 !== 0) return 'busy';   // one swing every 4 ticks
    const axe = this.axe();
    if (!axe) { this.say('You need an axe to chop down this tree.'); this.stop(); return ''; }
    if (this.inv.slots.indexOf(null) < 0 && !this.inv.slots.some(s => s && s.id === t.log && this.inv.stackable.has(t.log))) {
      this.say('Your inventory is too full to hold any more ' + this.inv.name(t.log).toLowerCase() + '.'); this.stop(); return '';
    }
    this.sfx && this.sfx.play('chop');
    a.cut += 4;
    const [low, high] = RATES[n.type][AXE_KEY[axe[0]]];
    if (this.rand() < successRate(low, high, this.skills.level('woodcutting'))) {
      this.inv.add(t.log, 1);
      this.say('You get some ' + this.inv.name(t.log).toLowerCase() + '.');
      this.skills.addXp('woodcutting', t.xp);
      if (t.fall === 0 || a.cut >= t.fall) {   // the tree falls and grows back later
        n.down = true; this.view.hide(n);
        const [lo, hi] = t.back; this.felled.push({ node: n, back: this.now + lo + Math.floor(this.rand() * (hi - lo + 1)) });
        this.stop(); return '';
      }
    }
    return 'busy';
  }
}
