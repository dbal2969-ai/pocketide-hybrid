// A branching skill tree (perks): points earned by levelling are spent on nodes; a node needs the nodes it lists in `requires`.
//   const tree = new SkillTree(design.skillTree); tree.points += 1; tree.unlock('power_strike');
//   tree.stat('damage') -> the sum of every owned node's effect.damage; tree.has('fireball') -> an owned node gives that ability
//   tree.mount(element) draws the branches as columns of buttons (owned, can buy, locked) and buys on tap.
// def: { branches: [{ id, name, color, nodes: [{ id, name, desc, cost: 1, requires: [ids], level: 0, effect: { damage, maxHp, speed, armor, ... , ability } }] }] }
// Part of PocketIDE's game kit; MIT licensed.
export class SkillTree {
  constructor(def = { branches: [] }, { onChange = () => {}, level = () => 99 } = {}) {
    this.def = def; this.points = 0; this.owned = new Set(); this.onChange = onChange; this.level = level;
    this.nodes = new Map(); for (const b of def.branches || []) for (const n of b.nodes || []) this.nodes.set(n.id, { cost: 1, requires: [], ...n, branch: b.id });
  }
  // why a node cannot be bought now, or null when it can
  why(id) {
    const n = this.nodes.get(id); if (!n) return 'No such skill.';
    if (this.owned.has(id)) return 'You already have ' + n.name + '.';
    const missing = (n.requires || []).filter(r => !this.owned.has(r)).map(r => (this.nodes.get(r) || { name: r }).name);
    if (missing.length) return n.name + ' needs ' + missing.join(' and ') + ' first.';
    if ((n.level || 0) > this.level()) return n.name + ' needs level ' + n.level + '.';
    if (this.points < n.cost) return 'Not enough skill points (' + n.name + ' costs ' + n.cost + ').';
    return null;
  }
  unlock(id) { if (this.why(id)) return false; const n = this.nodes.get(id); this.points -= n.cost; this.owned.add(id); this.onChange(n); this.render(); return true; }
  stat(name) { let s = 0; for (const id of this.owned) { const e = this.nodes.get(id)?.effect; if (e && typeof e[name] === 'number') s += e[name]; } return s; }
  has(ability) { for (const id of this.owned) { const e = this.nodes.get(id)?.effect; if (e && (e.ability === ability || (Array.isArray(e.abilities) && e.abilities.includes(ability)))) return true; } return false; }
  toJSON() { return { points: this.points, owned: [...this.owned] }; }
  load(s) { if (!s) return; this.points = s.points | 0; this.owned = new Set((s.owned || []).filter(id => this.nodes.has(id))); }

  mount(root, say = () => {}) { this.root = root; this.say = say; this.render(); }
  render() {
    const root = this.root; if (!root) return;
    root.innerHTML = '';
    const head = document.createElement('div'); head.className = 'st-head'; head.textContent = 'Skill points: ' + this.points; root.appendChild(head);
    const cols = document.createElement('div'); cols.className = 'st-cols'; root.appendChild(cols);
    for (const b of this.def.branches || []) {
      const col = document.createElement('div'); col.className = 'st-col'; col.style.borderColor = b.color || '#888';
      const t = document.createElement('div'); t.className = 'st-branch'; t.textContent = b.name || b.id; t.style.color = b.color || '#ddd'; col.appendChild(t);
      for (const n0 of b.nodes || []) {
        const n = this.nodes.get(n0.id), owned = this.owned.has(n.id), why = this.why(n.id);
        const el = document.createElement('div'); el.className = 'st-node ' + (owned ? 'owned' : why ? 'locked' : 'open'); el.dataset.skill = n.id;
        el.innerHTML = '<b></b><small></small>'; el.firstChild.textContent = n.name + (owned ? ' ✓' : n.cost > 1 ? ' (' + n.cost + ')' : '');
        el.lastChild.textContent = n.desc || '';
        el.addEventListener('pointerdown', e => { e.stopPropagation(); if (owned) { this.say(n.name + ': ' + (n.desc || '')); return; } const w = this.why(n.id); if (w) this.say(w); else { this.unlock(n.id); this.say('You learn ' + n.name + '.'); } });
        col.appendChild(el);
      }
      cols.appendChild(col);
    }
  }
}
