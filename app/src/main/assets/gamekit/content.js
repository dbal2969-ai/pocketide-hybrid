// Content: people to talk to, dialogue with choices, quests with stages and a journal, scripted actions on objects, NPCs and items,
// and timed skilling recipes (use an item on another item or on an object, with levels, tools, experience and a chance to fail).
// Everything comes from one data file, data/content.json (game_cache scaffold writes an empty one), so a game's content is data, not code:
//   { "skills": [{ id, label, short }]   optional: the game's own skills, in the order the Stats tab shows them
//     "people": [{ id, name, x, z, plane, walk, dialogue?, examine? }]
//     "groundItems": [{ id, name, amount, x, z, plane, respawnTicks }]
//     "quests": [{ id, name, points, done: 100, start: 1, requirements: [condition], journal: [step], rewards: { text: [..], do: [effect] } }]
//     "actions": [{ on: { object | npc | item: name or id, action } | { use: item id, on: { object | npc | item } }, if?, do: [effect], else?: [effect] }]
//     "recipes": [{ id, skill, level, xp, use, on: { item | object }, take: [[id, n]], give: [[id, n]], tools: [id], ticks, anim, success: [low, high], fail: { give, say }, say }]
//     "dialogues": { "<id>": { name?, open: [steps], nodes: { "<stage>": [steps] } } }   (or "dialogueFiles": ["<id>"] for data/content/dialogue/<id>.json)
//     "include": ["content/quests.json", ...] }   more files with the same sections, merged in (for big games)
// Conditions (a list means all of them): { has: id, n }, { lacks: id }, { free: slots }, { level: skill, at: n }, { stage: quest, op: '<' '<=' '==' '>=' '>' '!=', value }
//   or { stage: quest, in: [lo, hi] }, { done: quest }, { started: quest }, { var: name, is: value } or { var, op, value }, { points: n }, { chance: 0.25 },
//   { all: [..] }, { any: [..] }, { not: c }. Effects: { say: text }, { give: id, n }, { take: id, n }, { xp: skill, amount }, { stage: quest, set: n },
//   { start: quest }, { finish: quest }, { var: name, set | add | clear }, { dialogue: id }, { sound: name }, { anim: id }, { teleport: [x, z, plane] },
//   { heal: n }, { if: condition, then: [..], else: [..] }. Dialogue steps are effects plus: { npc: [lines], face }, { player: [lines] }, { text: [lines] },
//   { options: [labels], title }, { next: stage }, { end: true }, { button: { "1": [steps], "2": [steps] } } (the option the player picked).
// A dialogue works like the original servers: open runs first; each tap on "continue" (or an option) runs the node of the stage set by { next }.
// Written for PocketIDE's game kit; MIT licensed.

import { LABEL } from './skills.js';

async function json(url) { try { const r = await fetch(url); return r.ok ? await r.json() : null; } catch (e) { return null; } }
const lower = s => String(s == null ? '' : s).toLowerCase();
const list = v => v == null ? [] : Array.isArray(v) ? v : [v];
const cmp = (a, op, b) => op === '<' ? a < b : op === '<=' ? a <= b : op === '>' ? a > b : op === '>=' ? a >= b : op === '!=' ? a !== b : a === b;
// [[id, n]] or [{ id, n }] or [id] to [{ id, n }]
const stacks = v => list(v).map(x => Array.isArray(x) ? { id: x[0], n: x[1] ?? 1 } : typeof x === 'object' && x ? { id: x.id ?? x.item, n: x.n ?? x.amount ?? 1 } : { id: x, n: 1 });

// The chance that one try works, rising from low (level 1) to high (level 99), out of 256: the original games' formula.
export function successChance(low, high, level) { return Math.min(1, (1 + Math.floor(low * (99 - level) / 98 + high * (level - 1) / 98 + 0.5)) / 256); }

export class Content {
  // o: { inv, skills, say, sfx, playerName, fx(kind, info), teleport(x, z, plane), heal(n), onChange() }
  constructor(o) {
    this.o = o; this.state = { quests: {}, vars: {} };
    this.people = []; this.recipes = []; this.actions = []; this.quests = {}; this.questList = []; this.dialogues = {};
    this.making = null; this.talk = null; this.warned = new Set(); this.rand = o.rand || Math.random;
  }
  // root: data/content.json already read (main.js reads it first for the skills), or null to read it here
  async load(url = 'data/content.json', root) {
    const dir = url.replace(/[^/]*$/, ''); this.base = dir + 'content/';
    root = root === undefined ? await json(url) : root;
    this.dialogueFiles = new Set(); this.groundItems = [];
    const parts = [root || {}];
    for (const inc of list(root && root.include)) { const j = await json(dir + inc); if (j) parts.push(j); else this.warn('missing include ' + inc); }
    for (const p of parts) {
      this.people.push(...list(p.people)); this.recipes.push(...list(p.recipes)); this.actions.push(...list(p.actions)); this.groundItems.push(...list(p.groundItems));
      for (const q of list(p.quests)) { this.questList.push(q); this.quests[q.id] = q; }
      Object.assign(this.dialogues, p.dialogues || {}); for (const d of list(p.dialogueFiles)) this.dialogueFiles.add(d);
    }
    return this;
  }
  loadState(s) { if (s && typeof s === 'object') { this.state.quests = s.quests || {}; this.state.vars = s.vars || {}; } }
  toJSON() { return this.state; }
  changed() { this.o.onChange && this.o.onChange(); }
  warn(what) { if (!this.warned.has(what)) { this.warned.add(what); console.warn('content: ' + what); } }
  say(t) { this.o.say(this.fill(t)); }
  fill(t) { return String(t).replace(/\{(?:player\.)?(?:username|name|playerName)\}/gi, this.o.playerName || 'adventurer'); }

  // ---------------------------------------------------------------- quests
  stage(q) { return this.state.quests[q] | 0; }
  doneStage(q) { const d = this.quests[q]; return d && d.done != null ? d.done : 100; }
  status(q) { const s = this.stage(q); return s <= 0 ? 'not started' : s >= this.doneStage(q) ? 'done' : 'started'; }
  points() { return this.questList.reduce((n, q) => n + (this.status(q.id) === 'done' ? (q.points | 0) : 0), 0); }
  questName(q) { return (this.quests[q] && this.quests[q].name) || q; }
  setStage(q, n) {
    const before = this.stage(q);
    if (n >= this.doneStage(q) && before < this.doneStage(q)) { this.finish(q); return; }
    this.state.quests[q] = n;
    if (before <= 0 && n > 0) { this.say('You have started a new quest: ' + this.questName(q) + '.'); this.o.sfx && this.o.sfx.play('click'); }
    this.changed();
  }
  canStart(q) { const d = this.quests[q]; return !d || this.check(d.requirements); }
  finish(q) {
    if (this.status(q) === 'done') return;
    const d = this.quests[q] || {};
    this.state.quests[q] = this.doneStage(q);
    if (d.rewards && d.rewards.do) this.run(d.rewards.do);
    this.say('Congratulations! Quest complete: ' + this.questName(q) + '.');
    const text = (d.rewards && d.rewards.text) || [];
    if (text.length) this.say('You are awarded: ' + text.join(', ') + '.');
    this.o.sfx && this.o.sfx.play('levelup');
    this.o.fx && this.o.fx('quest', { id: q, rewards: text });
    this.changed();
  }
  // the lines of a quest's journal that apply now: steps with { if } are kept when they hold; { line } or strings are text
  journal(q) {
    const d = this.quests[q]; if (!d) return [];
    const out = [];
    const walk = steps => { for (const s of list(steps)) {
      if (typeof s === 'string') out.push({ text: s });
      else if (s && 'if' in s) walk(this.check(s.if) ? s.then : s.else);
      else if (s && 'line' in s) out.push({ text: s.line, done: !!s.done });
      else if (s && 'lines' in s) for (const l of list(s.lines)) out.push({ text: l, done: !!s.done });
    } };
    walk(d.journal);
    if (!out.length) out.push({ text: this.status(q) === 'done' ? 'QUEST COMPLETE!' : this.status(q) === 'started' ? 'I have started this quest.' : 'I have not started this quest yet.' });
    return out.map(l => ({ ...l, text: this.fill(l.text) }));
  }

  // ---------------------------------------------------------------- conditions and effects
  free() { return this.o.inv.slots.filter(s => !s).length; }
  check(c, ctx = {}) {
    if (c == null) return true;
    if (Array.isArray(c)) return c.every(x => this.check(x, ctx));
    if (typeof c !== 'object') return !!c;
    const { inv, skills } = this.o;
    if ('all' in c) return list(c.all).every(x => this.check(x, ctx));
    if ('any' in c) return list(c.any).some(x => this.check(x, ctx));
    if ('not' in c) return !this.check(c.not, ctx);
    if ('has' in c) return inv.count(c.has) >= (c.n ?? 1);
    if ('lacks' in c) return inv.count(c.lacks) < (c.n ?? 1);
    if ('free' in c) return this.free() >= c.free;
    if ('level' in c) return skills.level(c.level) >= (c.at ?? c.value ?? 1);
    if ('done' in c) return this.status(c.done) === 'done';
    if ('started' in c) return this.stage(c.started) > 0;
    if ('stage' in c) { const s = this.stage(c.stage); return c.in ? s >= c.in[0] && s <= c.in[1] : cmp(s, c.op || '==', c.value | 0); }
    if ('var' in c) { const v = this.state.vars[c.var];
      if ('is' in c) return c.is === false || c.is === 0 || c.is == null ? !v : v === c.is || (c.is === true && !!v);
      if (c.op) return cmp(v ?? 0, c.op, c.value); return !!v; }
    if ('points' in c) return this.points() >= c.points;
    if ('chance' in c) return this.rand() < c.chance;
    if ('button' in c) return ctx.button === c.button;
    if ('code' in c) { this.warn('untranslated condition (counts as false): ' + c.code); return false; }
    this.warn('unknown condition ' + JSON.stringify(c)); return false;
  }
  // removes n of an item from wherever it is; false (and nothing taken) when there are not enough
  take(id, n = 1) {
    const inv = this.o.inv; if (inv.count(id) < n) return false;
    let left = n;
    for (let i = 0; i < inv.slots.length && left > 0; i++) { const s = inv.slots[i]; if (s && s.id === id) left -= inv.remove(i, left).count; }
    return true;
  }
  give(id, n = 1) { const left = this.o.inv.add(id, n); if (left) this.say("You don't have enough inventory space."); return !left; }
  run(effects, ctx = {}) {
    const o = this.o;
    for (const e of list(effects)) {
      if (!e || typeof e !== 'object') continue;
      if ('if' in e) { this.run(this.check(e.if, ctx) ? e.then : e.else, ctx); continue; }
      if ('say' in e) this.say(e.say);
      if ('give' in e) this.give(e.give, e.n ?? 1);
      if ('take' in e) this.take(e.take, e.n ?? 1);
      if ('xp' in e) o.skills.addXp(e.xp, e.amount ?? e.n ?? 0);
      if ('stage' in e && 'set' in e) this.setStage(e.stage, e.set);
      if ('start' in e && this.stage(e.start) <= 0) this.setStage(e.start, (this.quests[e.start] && this.quests[e.start].start) || 1);
      if ('finish' in e) this.finish(e.finish);
      if ('var' in e) { if ('set' in e) this.state.vars[e.var] = e.set; else if ('add' in e) this.state.vars[e.var] = (this.state.vars[e.var] | 0) + e.add; else if (e.clear) delete this.state.vars[e.var]; this.changed(); }
      if ('dialogue' in e) this.openDialogue(e.dialogue, ctx.person);
      if ('sound' in e) o.sfx && o.sfx.play(e.sound);
      if ('anim' in e) o.fx && o.fx('anim', { id: e.anim });
      if ('teleport' in e) o.teleport && o.teleport(e.teleport[0], e.teleport[1], e.teleport[2] | 0);
      if ('heal' in e) o.heal && o.heal(e.heal);
      if ('code' in e) this.warn('untranslated effect (skipped): ' + e.code);
    }
  }

  // ---------------------------------------------------------------- people and dialogue
  // A tap on a person: their dialogue (people.json "dialogue", else dialogue/npc_<id>.json), else a line saying they will not talk.
  async talkTo(person) {
    this.stop();
    const id = person.dialogue || 'npc_' + person.id;
    const ok = await this.openDialogue(id, person, true);
    if (!ok) this.say((person.name || 'They') + ' does not seem interested in talking.');
  }
  async dialogue(id) {
    if (!(id in this.dialogues) && this.dialogueFiles && this.dialogueFiles.has(id)) this.dialogues[id] = await json(this.base + 'dialogue/' + id + '.json');
    return this.dialogues[id] || null;
  }
  async openDialogue(id, person, quiet) {
    const d = await this.dialogue(id);
    if (!d) { if (!quiet) this.warn('no dialogue ' + id); return false; }
    this.talk = { d, person: person || null, name: (person && person.name) || d.name || '', stage: undefined, next: undefined, ending: false, shown: null };
    this.steps(d.open || [], {});
    this.after();
    return true;
  }
  // runs dialogue steps: the first message shown is kept for the box; { next } picks the node that "continue" runs
  steps(list0, ctx) {
    const t = this.talk; if (!t) return;
    for (const s of list(list0)) {
      if (!s || typeof s !== 'object') continue;
      if ('npc' in s || 'player' in s || 'text' in s || 'item' in s && 'lines' in s) {
        const who = 'npc' in s ? 'npc' : 'player' in s ? 'player' : 'text';
        if (!t.shown) t.shown = { who, lines: list(s[who] ?? s.lines).map(l => this.fill(l)), face: s.face, name: s.name };
        continue;
      }
      if ('options' in s) { if (!t.shown) t.shown = { who: 'options', lines: list(s.options).map(l => this.fill(l)), title: s.title }; continue; }
      if ('next' in s) { t.next = s.next; continue; }
      if (s.end) { t.ending = true; continue; }
      if ('button' in s) { this.steps(s.button[ctx.button] || s.button[String(ctx.button)] || [], ctx); continue; }
      if ('if' in s && ('then' in s || 'else' in s)) { this.steps(this.check(s.if, ctx) ? s.then : s.else, ctx); continue; }
      this.run([s], { ...ctx, person: t.person });
    }
  }
  after() {
    const t = this.talk; if (!t) return;
    if (!t.shown) { this.close(); return; }
    t.stage = t.next; t.next = undefined;
    if (t.stage === undefined) t.ending = true;
    this.show(t);
  }
  // "continue" or an option (button 1, 2, ...)
  next(button) {
    const t = this.talk; if (!t) return;
    if (t.shown && t.shown.who === 'options' && !button) return;   // pick an option first
    if (t.pick) { const r = t.pick[button - 1]; this.close(); if (r) this.make(r); return; }   // "What would you like to make?"
    if (t.ending) { this.close(); return; }
    const node = t.d.nodes && t.d.nodes[t.stage];
    t.shown = null;
    if (!node) { this.close(); return; }
    this.steps(node, { button });
    this.after();
  }
  close() { this.talk = null; this.hide(); }
  get talking() { return !!this.talk; }

  // the dialogue box, over the chat box
  mount(root = document.body) {
    const box = document.createElement('div'); box.id = 'dialog'; box.hidden = true; root.appendChild(box); this.box = box;
    box.addEventListener('pointerdown', e => { e.stopPropagation(); if (this.talk && this.talk.shown && this.talk.shown.who !== 'options') this.next(); });
    addEventListener('keydown', e => { if (this.talk && e.key === ' ') { e.preventDefault(); this.next(); } if (this.talk && /^[1-5]$/.test(e.key) && this.talk.shown && this.talk.shown.who === 'options') this.next(+e.key); });
  }
  show(t) {
    const box = this.box; if (!box) return;
    box.hidden = false; box.innerHTML = '';
    const s = t.shown, el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
    if (s.who === 'options') {
      box.appendChild(el('div', 'dname', s.title || 'Select an Option'));
      s.lines.forEach((l, i) => { const b = el('div', 'dopt', l); b.dataset.n = i + 1; b.addEventListener('pointerdown', e => { e.stopPropagation(); this.next(i + 1); }); box.appendChild(b); });
      return;
    }
    const name = s.name || (s.who === 'npc' ? t.name : s.who === 'player' ? (this.o.playerName || 'You') : '');
    if (name) box.appendChild(el('div', 'dname', name));
    for (const l of s.lines) box.appendChild(el('div', 'dline', l));
    box.appendChild(el('div', 'dcont', 'Click here to continue'));
    box.dataset.who = s.who;
  }
  hide() { if (this.box) { this.box.hidden = true; this.box.innerHTML = ''; } }

  // ---------------------------------------------------------------- scripted actions
  matches(on, kind, target, action) {
    if (!on || !(kind in on)) return false;
    if (on.action && lower(on.action) !== lower(action)) return false;
    return list(on[kind]).some(w => typeof w === 'number' ? w === target.id : lower(w) === lower(target.name));
  }
  // An action of an object, NPC or item from actions.json; true when one applied
  act(kind, target, action, ctx = {}) {
    let hit = false;
    for (const a of this.actions) {
      if (a.on && 'use' in a.on) continue;
      if (!this.matches(a.on, kind, target, action)) continue;
      hit = true;
      this.run(this.check(a.if, ctx) ? a.do : a.else, { ...ctx, target });
      break;
    }
    return hit;
  }
  // the actions this game scripts for a target (for menus)
  actionsFor(kind, target) { return [...new Set(this.actions.filter(a => a.on && !('use' in a.on) && this.matches({ ...a.on, action: null }, kind, target, null)).map(a => a.on.action).filter(Boolean))]; }

  // Use an inventory item on a target: { item: { id, name } } (another item), { object: { id, name } } or { npc: { id, name } }.
  // A scripted use in actions.json wins; then recipes (several matching recipes ask which to make). True when something applied.
  useOn(itemId, target) {
    const kind = Object.keys(target)[0], t = target[kind];
    for (const a of this.actions) {
      if (!a.on || !('use' in a.on)) continue;
      if (!list(a.on.use).includes(itemId) || !this.matches(a.on.on || {}, kind, t, null)) continue;
      this.run(this.check(a.if) ? a.do : a.else, { target: t });
      return true;
    }
    const found = this.recipesFor(itemId, kind, t);
    if (!found.length) return false;
    if (found.length === 1) { this.make(found[0]); return true; }
    this.choose(found);
    return true;
  }
  // true when using itemId on the target would do something (a scripted use or a recipe)
  canUse(itemId, kind, t) {
    return this.actions.some(a => a.on && 'use' in a.on && list(a.on.use).includes(itemId) && this.matches(a.on.on || {}, kind, t, null)) || this.recipesFor(itemId, kind, t).length > 0;
  }
  recipesFor(itemId, kind, t) {
    return this.recipes.filter(r => {
      const use = list(r.use), on = r.on || {};
      if (kind === 'item') return (use.includes(itemId) && list(on.item).includes(t.id)) || (use.includes(t.id) && list(on.item).includes(itemId));
      if (kind === 'object') return use.includes(itemId) && 'object' in on && list(on.object).some(w => typeof w === 'number' ? w === t.id : lower(t.name).includes(lower(w)));
      if (kind === 'npc') return use.includes(itemId) && 'npc' in on && list(on.npc).some(w => typeof w === 'number' ? w === t.id : lower(w) === lower(t.name));
      return false;
    });
  }
  // recipes done by an item's own option (Bury, Eat, Clean...)
  itemAction(itemId, action) {
    const r = this.recipes.filter(r => list(r.use).includes(itemId) && r.action && lower(r.action) === lower(action) && !r.on);
    if (r.length) { this.make(r[0], 1); return true; }
    return this.act('item', { id: itemId, name: this.o.inv.name(itemId) }, action);
  }
  itemActions(itemId) { return [...new Set(this.recipes.filter(r => list(r.use).includes(itemId) && r.action && !r.on).map(r => r.action).concat(this.actionsFor('item', { id: itemId, name: this.o.inv.name(itemId) })))]; }
  choose(found) {
    this.talk = { d: { nodes: {} }, person: null, name: '', stage: undefined, ending: false, shown: { who: 'options', title: 'What would you like to make?', lines: found.map(r => this.o.inv.name(stacks(r.give)[0]?.id) + (r.level > 1 ? ' (level ' + r.level + ')' : '')) } };
    this.talk.pick = found;
    this.show(this.talk);
  }

  // ---------------------------------------------------------------- timed recipes (one make every r.ticks game ticks, all of them by default)
  why(r) {
    const { skills, inv } = this.o;
    if (r.skill && skills.level(r.skill) < (r.level | 0)) return 'You need a ' + label(r.skill) + ' level of ' + r.level + ' to do that.';
    for (const t of list(r.tools)) if (!inv.count(t)) return 'You need a ' + inv.name(t) + ' to do that.';
    if (r.if && !this.check(r.if)) return r.ifSay || "You can't do that yet.";
    for (const s of this.inputs(r)) if (inv.count(s.id) < s.n) return 'You have run out of ' + inv.name(s.id) + '.';
    return null;
  }
  inputs(r) { return r.take ? stacks(r.take) : list(r.use).length ? [{ id: list(r.use)[0], n: 1 }] : []; }
  make(r, count) {
    this.stop();
    const no = this.why(r);
    if (no) { this.say(no); return false; }
    this.making = { r, left: count ?? r.make ?? 28, wait: 1 };
    this.o.fx && this.o.fx('make', { recipe: r });
    return true;
  }
  stop() { if (this.making) { this.making = null; this.o.fx && this.o.fx('make', null); } }
  // one game tick; returns true while something is being made
  tick() {
    const m = this.making; if (!m) return false;
    if (--m.wait > 0) return true;
    const r = m.r, no = this.why(r);
    if (no) { if (m.made) this.say(no); this.stop(); return false; }
    for (const s of this.inputs(r)) this.take(s.id, s.n);
    const level = r.skill ? this.o.skills.level(r.skill) : 1;
    const ok = !r.success || this.rand() < successChance(r.success[0], r.success[1], level);
    if (ok) {
      for (const s of stacks(r.give)) this.give(s.id, s.n);
      if (r.skill && r.xp) this.o.skills.addXp(r.skill, r.xp);
      if (r.say) this.say(r.say); else { const g = stacks(r.give)[0]; if (g) this.say('You make ' + (g.n > 1 ? g.n + ' x ' : 'a ') + this.o.inv.name(g.id).toLowerCase() + '.'); }
      if (r.do) this.run(r.do);
    } else {
      const f = r.fail || {};
      for (const s of stacks(f.give)) this.give(s.id, s.n);
      this.say(f.say || 'You fail.');
      if (f.xp && r.skill) this.o.skills.addXp(r.skill, f.xp);
    }
    this.o.sfx && this.o.sfx.play(ok ? 'click' : 'miss');
    m.made = (m.made | 0) + 1;
    if (--m.left <= 0) { this.stop(); return false; }
    m.wait = r.ticks || 4;
    return true;
  }
}

const label = s => LABEL[s] || String(s).charAt(0).toUpperCase() + String(s).slice(1);
