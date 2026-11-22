// Skills, levels and experience, with the real experience table (level 10 = 1,154 xp, level 99 = 13,034,431 xp).
// Written for PocketIDE's game kit; MIT licensed.

// XP_AT[level] = total experience needed to reach that level.
export const XP_AT = (() => {
  const t = [0, 0]; let sum = 0;
  for (let l = 1; l < 99; l++) { sum += Math.floor(l + 300 * Math.pow(2, l / 7)); t[l + 1] = Math.floor(sum / 4); }
  return t;
})();

export function levelFor(xp) { let l = 1; while (l < 99 && xp >= XP_AT[l + 1]) l++; return l; }

// The skills this game has, in the order the Stats tab shows them (three to a row, as in the original game). A game with
// other skills calls defineSkills() before making Skills (main.js does it from data/content/skills.json); a skill that gains
// experience without being listed is added at the end.
export const ORDER = ['attack', 'hitpoints', 'mining', 'strength', 'agility', 'smithing', 'defence', 'herblore', 'fishing', 'ranged', 'thieving', 'cooking',
  'prayer', 'crafting', 'firemaking', 'magic', 'fletching', 'woodcutting', 'runecrafting', 'slayer', 'farming', 'construction', 'hunter'];
export const LABEL = {};   // full names
export const SHORT = {};   // for the narrow Stats tab
const SHORTER = { hitpoints: 'Hitpts', woodcutting: 'Woodcut', runecrafting: 'Runecr', construction: 'Constr', firemaking: 'Firemk', fletching: 'Fletch', summoning: 'Summon' };
export function addSkill(id, label, short) {
  if (!ORDER.includes(id)) ORDER.push(id);
  LABEL[id] = label || LABEL[id] || id.charAt(0).toUpperCase() + id.slice(1).replace(/_/g, ' ');
  SHORT[id] = short || SHORT[id] || SHORTER[id] || (LABEL[id].length > 8 ? LABEL[id].slice(0, 7) : LABEL[id]);
}
for (const s of ORDER) addSkill(s);
LABEL.runecrafting = 'Runecraft';
// replaces the skill list: [{ id, label, short }] or ['name', ...]
export function defineSkills(list) {
  if (!Array.isArray(list) || !list.length) return;
  ORDER.length = 0;
  for (const s of list) typeof s === 'string' ? addSkill(s) : addSkill(s.id, s.label, s.short);
}

export class Skills {
  // start: levels to begin with, e.g. { attack: 85 }; a skill left out starts at level 1 (hitpoints at 10, like the real game).
  constructor(start = {}) {
    this.xp = {};
    for (const s of ORDER) this.xp[s] = XP_AT[Math.max(1, Math.min(99, start[s] || (s === 'hitpoints' ? 10 : 1)))];
    this.onLevel = () => {};   // (skill, newLevel)
  }
  level(s) { return levelFor(this.xp[s] || 0); }
  // Adds experience; returns true when it raised the level. The experience of one skill never goes above 200 million.
  addXp(s, n) {
    if (!(n > 0) || !s) return false;
    if (!(s in this.xp)) { addSkill(s); this.xp[s] = 0; }
    const before = this.level(s);
    this.xp[s] = Math.min(200_000_000, this.xp[s] + n);
    const after = this.level(s);
    if (after > before) { this.onLevel(s, after); return true; }
    return false;
  }
  // experience still needed for the next level (0 at 99)
  toNext(s) { const l = this.level(s); return l >= 99 ? 0 : Math.ceil(XP_AT[l + 1] - (this.xp[s] || 0)); }
  total() { return ORDER.reduce((n, s) => n + this.level(s), 0); }
  toJSON() { const o = {}; for (const s of ORDER) o[s] = this.xp[s]; return o; }
  load(o) { if (o) for (const s of Object.keys(o)) if (typeof o[s] === 'number' && o[s] >= 0) { if (!ORDER.includes(s)) addSkill(s); this.xp[s] = o[s]; } }
}
