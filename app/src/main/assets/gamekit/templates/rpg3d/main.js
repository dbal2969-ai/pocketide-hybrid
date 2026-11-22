// An open-world 3D role-playing game made from design.json: villages with houses of several floors, dungeons under the ground,
// creatures that roam, fight or flee, a hero with a sword and spells, experience and levels, a branching skill tree, quests and
// dialogue (data/content.json, played by content.js), loot, chests and saving. Change the world by editing design.json and
// data/content.json; the code rarely needs to change. Controls: joystick or WASD to move, drag the right side to turn the camera,
// A / Space to attack, E to use (talk, stairs, doors, chests), F for a fireball once learned, the ≡ button for skills, quests and bag.
// Play tests read window.__game (see tests/play.txt). Written for PocketIDE's game kit; MIT licensed.
import * as THREE from './engine/three.module.min.js';
import { Loop } from './core/loop.js';
import { Input } from './core/input.js';
import { text, bar } from './core/ui.js';
import { Save } from './core/save.js';
import { buildWorld } from './core/worldgen.js';
import { makeCreature, LOOKS } from './core/creatures.js';
import { SkillTree } from './core/skilltree.js';
import { Content } from './content.js';
import { Inventory } from './inv.js';
import { Skills, defineSkills, XP_AT } from './skills.js';

const json = async url => { try { const r = await fetch(url); return r.ok ? await r.json() : null; } catch (e) { return null; } };
const view = document.getElementById('view'), hud = document.getElementById('hud'), hc = hud.getContext('2d');
const renderer = new THREE.WebGLRenderer({ canvas: view, antialias: true });
const scene = new THREE.Scene(), SKY = new THREE.Color('#9cc8ec'), CAVE = new THREE.Color('#0c0a0a');
scene.background = SKY.clone(); scene.fog = new THREE.Fog(SKY.clone(), 40, 140);
const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 400);
function fit() { renderer.setPixelRatio(Math.min(devicePixelRatio, 2)); renderer.setSize(innerWidth, innerHeight, false); hud.width = innerWidth; hud.height = innerHeight; camera.aspect = innerWidth / innerHeight; camera.updateProjectionMatrix(); }
addEventListener('resize', fit); fit();
const hemi = new THREE.HemisphereLight(0xffffff, 0x445533, 0.95); scene.add(hemi);
const sun = new THREE.DirectionalLight(0xfff4e0, 0.9); sun.position.set(40, 80, 30); scene.add(sun);
const torch = new THREE.PointLight(0xffaa55, 0, 22, 1); scene.add(torch);

// tiny sounds made in code (no files): beep(frequency, seconds, wave, volume)
let actx = null; addEventListener('pointerdown', () => { if (!actx) { const C = window.AudioContext || window.webkitAudioContext; if (C) actx = new C(); } }, { once: true });
function beep(f, d = 0.1, type = 'square', v = 0.05, slide = 0) {
  if (!actx || settings.muted) return; const o = actx.createOscillator(), g = actx.createGain(), t = actx.currentTime;
  o.type = type; o.frequency.setValueAtTime(f, t); if (slide) o.frequency.exponentialRampToValueAtTime(Math.max(30, f + slide), t + d);
  g.gain.setValueAtTime(v, t); g.gain.exponentialRampToValueAtTime(0.0001, t + d); o.connect(g); g.connect(actx.destination); o.start(t); o.stop(t + d);
}
const SFX = { swing: () => beep(220, 0.08, 'triangle', 0.05, -120), hit: () => beep(140, 0.12, 'square', 0.06, -80), hurt: () => beep(90, 0.18, 'sawtooth', 0.05, -40),
  coin: () => { beep(880, 0.06, 'square', 0.04); setTimeout(() => beep(1320, 0.08, 'square', 0.04), 60); }, level: () => [523, 659, 784, 1046].forEach((f, i) => setTimeout(() => beep(f, 0.15, 'triangle', 0.06), i * 110)),
  fire: () => beep(300, 0.3, 'sawtooth', 0.05, -250), click: () => beep(660, 0.04, 'square', 0.03), die: () => beep(200, 0.5, 'triangle', 0.06, -170) };
const settings = { muted: false };

(async () => {
  const design = (await json('design.json')) || {};
  const contentData = await json('data/content.json');
  defineSkills((contentData && contentData.skills) || [{ id: 'level', label: 'Level' }]);
  const world = buildWorld(THREE, scene, design);
  const P = { name: 'Adventurer', hp: 100, damage: 8, speed: 5, ...(design.player || {}) };
  const save = new Save('rpg3d:' + (design.title || 'game'), 1), saved = save.read('main', null) || {};

  // ---- messages in the corner ----
  const log = [];
  const say = t => { log.unshift({ t: String(t), at: performance.now() }); log.length = Math.min(log.length, 6); };

  // ---- inventory, levels, skill tree, content (quests and dialogue) ----
  const inv = new Inventory(28, [995]); inv.setNames(design.items || []);
  const itemDef = id => (design.items || []).find(i => i.id === id) || {};
  if (Array.isArray(saved.inv) && saved.inv.length === 28) inv.slots = saved.inv; else inv.add(1, 2);
  const skills = new Skills({}); skills.load(saved.xp);
  const tree = new SkillTree(design.skillTree, { level: () => skills.level('level'), onChange: () => { refreshStats(); SFX.level(); } });
  tree.load(saved.tree);
  skills.onLevel = (s, l) => { if (s === 'level') { tree.points += 1; say('Level up! You are now level ' + l + '. You have a skill point to spend (≡ menu).'); SFX.level(); refreshStats(); player.hp = player.maxHp; } };
  const content = await new Content({ inv, skills, say, sfx: { play: k => (SFX[k] || SFX.click)() }, playerName: P.name, heal: n => { player.hp = Math.min(player.maxHp, player.hp + n); } }).load('data/content.json', contentData);
  content.loadState(saved.content); content.mount();

  // ---- the hero ----
  const hero = makeCreature(THREE, { ...(LOOKS[P.look] || LOOKS.guard), weapon: 'sword', ...(P.color ? { color: P.color } : {}), ...(P.accent ? { accent: P.accent } : {}) });
  scene.add(hero.group);
  const startV = world.villages.find(v => v.name === P.start) || world.villages[0] || { x: 0, z: 0 };
  const player = { x: startV.x + 4, z: startV.z + 4, y: 0, heading: 0, level: 0, house: null, dungeon: null, hp: P.hp, maxHp: P.hp, cool: 0, spellCool: 0, dead: 0, gold: 0, moved: 0 };
  if (saved.pos) Object.assign(player, { x: saved.pos.x, z: saved.pos.z, level: saved.pos.level | 0, dungeon: saved.pos.dungeon ?? null, house: saved.pos.house ?? null });
  function refreshStats() { player.maxHp = P.hp + tree.stat('maxHp'); player.hp = Math.min(player.hp, player.maxHp); }
  refreshStats(); if (saved.hp) player.hp = Math.min(player.maxHp, saved.hp);

  // ---- creatures and people ----
  const creatures = [], people = [];
  const cdef = name => ({ look: name, hp: 20, damage: 4, speed: 3, xp: 10, aggro: 0, ...((design.creatures || {})[name] || {}) });
  function spawn(type, x, z, dungeon = null, extra = {}) {
    const d = cdef(type), look = { ...(LOOKS[d.look] || LOOKS[type] || LOOKS.villager), ...(d.size ? { size: d.size } : {}), ...(d.color ? { color: d.color } : {}) };
    const c = makeCreature(THREE, look); scene.add(c.group);
    const k = { type, name: d.name || type.replace(/_/g, ' '), def: d, c, x, z, home: { x, z }, dungeon, hp: d.hp, maxHp: d.hp, cool: 0, wander: null, heading: Math.random() * 6, dead: false, deadAt: 0, ...extra };
    creatures.push(k); return k;
  }
  for (const w of design.wildlife || []) for (const p of world.spots(w.count || 1, w.area || {})) spawn(w.creature, p.x, p.z);
  for (const dg of world.dungeons) {
    dg.rooms.forEach((rm, i) => {
      if (i === 0) return;
      if (rm === dg.bossRoom && dg.def.boss) { const p = dg.roomSpot(rm, 1); spawn(dg.def.boss, p.x, p.z, dg.index); return; }
      const n = 1 + (i % 2), list = dg.def.creatures.length ? dg.def.creatures : ['skeleton'];
      for (let k = 0; k < n; k++) { const p = dg.roomSpot(rm, k); spawn(list[(i + k) % list.length], p.x, p.z, dg.index); }
    });
  }
  for (const v of world.villages) {
    const spots = world.spots(v.people.length, { village: v.name });
    v.people.forEach((pp, i) => {
      const p = spots[i] || { x: v.x + 3 + i, z: v.z - 3 };
      const look = { ...(LOOKS[pp.look] || LOOKS.villager), ...(pp.color ? { color: pp.color } : {}), ...(pp.accent ? { accent: pp.accent } : {}), ...(pp.weapon ? { weapon: pp.weapon } : {}) };
      const c = makeCreature(THREE, look); scene.add(c.group);
      people.push({ ...pp, c, x: p.x, z: p.z, home: { ...p }, heading: Math.random() * 6, wander: null, village: v.name });
    });
  }
  const hostile = k => !k.dead && (k.def.aggro > 0 || k.angry) && !k.def.flee;

  // ---- input ----
  const input = new Input(hud);
  input.bind('attack', ['Space', 'KeyJ']); input.bind('use', ['KeyE']); input.bind('spell', ['KeyF']); input.bind('menu', ['KeyM', 'Tab']);
  input.addButton('attack', 'A', (w, h) => [w - 64, h - 76], 38);
  input.addButton('use', 'E', (w, h) => [w - 140, h - 56], 30);
  input.addButton('spell', 'F', (w, h) => [w - 64, h - 160], 30);
  let yaw = 0, drag = null;   // camera turn: drag on the right half of the screen
  hud.addEventListener('pointerdown', e => { if (e.clientX > innerWidth * 0.5 && !input.buttons.some(b => b.id === e.pointerId)) drag = { id: e.pointerId, x: e.clientX }; });
  hud.addEventListener('pointermove', e => { if (drag && e.pointerId === drag.id) { yaw -= (e.clientX - drag.x) * 0.008; drag.x = e.clientX; } });
  hud.addEventListener('pointerup', e => { if (drag && e.pointerId === drag.id) drag = null; });
  addEventListener('keydown', e => { if (e.code === 'KeyQ') yaw += 0.15; if (e.code === 'KeyR') yaw -= 0.15; });

  // ---- where the player is: surface, a floor of a house, or a dungeon ----
  const where = () => ({ level: player.level, house: player.house, dungeon: player.dungeon });
  function groundY(x, z, lvl = player.level, house = player.house, dungeon = player.dungeon) {
    if (dungeon != null) return 0;
    const h = house != null ? world.houses[house] : null;
    if (h && lvl > 0) return h.y + lvl * world.FLOOR_H - 0.15;
    if (h && world.houseAt(x, z) === h) return h.y;
    return world.heightAt(x, z);
  }
  function enterDungeon(i) {
    const dg = world.dungeons[i]; if (!dg) return;
    player.dungeon = i; player.level = 0; player.house = null; player.x = dg.start.x; player.z = dg.start.z + 1.5;
    say('You enter ' + dg.name + '.'); SFX.click(); setArea();
  }
  function leaveDungeon() {
    const dd = world.dungeons[player.dungeon]?.def; player.dungeon = null;
    if (dd) { player.x = dd.x; player.z = dd.z + 3.2; }
    say('You climb back into the daylight.'); SFX.click(); setArea();
  }
  function setArea() {
    const inDg = player.dungeon != null;
    for (const dg of world.dungeons) { const on = dg.index === player.dungeon; dg.mesh.visible = on; dg.flames.visible = on; for (const m of dg.extra || []) m.visible = on && !(m.userData.opened); }
    scene.background.copy(inDg ? CAVE : SKY); scene.fog.color.copy(inDg ? CAVE : SKY); scene.fog.near = inDg ? 6 : 40; scene.fog.far = inDg ? 30 : 140;
    hemi.intensity = inDg ? 0.55 : 0.95; sun.intensity = inDg ? 0 : 0.9; torch.intensity = inDg ? 9 : 0;
  }
  setArea();

  // ---- interactions: the nearest thing the E button would use ----
  const opened = new Set(saved.opened || []);
  for (const it of world.interactables) if (it.kind === 'chest' && opened.has(it.id) && it.mesh) { it.mesh.userData.opened = true; it.mesh.visible = false; }
  function nearest() {
    let best = null, bd = 2.2;
    for (const it of world.interactables) {
      if ((it.inside ? it.dungeon : null) !== player.dungeon) continue;
      if (it.dungeon != null && !it.inside && player.dungeon != null) continue;
      if ((it.level || 0) !== player.level || (it.house != null && it.house !== player.house && player.level > 0)) continue;
      if (it.kind === 'chest' && opened.has(it.id)) continue;
      const d = Math.hypot(it.x - player.x, it.z - player.z); if (d < bd) { bd = d; best = it; }
    }
    if (player.dungeon == null) for (const p of people) { const d = Math.hypot(p.x - player.x, p.z - player.z); if (d < Math.max(bd, 2.4) && player.level === 0) { bd = d; best = { kind: 'talk', label: 'Talk to ' + p.name, person: p }; } }
    return best;
  }
  function use(it = nearest()) {
    if (!it) { say('There is nothing here to use.'); return false; }
    if (it.kind === 'talk') { it.person.talking = true; content.talkTo({ id: it.person.id, name: it.person.name, dialogue: it.person.dialogue }); return true; }
    if (it.kind === 'stairsUp' || it.kind === 'stairsDown') {
      player.house = it.house; player.level = it.to; const h = world.houses[it.house], o = world.interactables.find(x => x.house === it.house && x.level === it.to && x.kind === (it.kind === 'stairsUp' ? 'stairsDown' : 'stairsUp'));
      if (o) { player.x = o.x + (h.x - o.x) * 0.25; player.z = o.z + (h.z - o.z) * 0.25; }
      say(it.kind === 'stairsUp' ? 'You climb the stairs.' : 'You climb down.'); SFX.click(); return true;
    }
    if (it.kind === 'enterDungeon') { enterDungeon(it.dungeon); return true; }
    if (it.kind === 'leaveDungeon') { leaveDungeon(); return true; }
    if (it.kind === 'chest') {
      opened.add(it.id); if (it.mesh) { it.mesh.userData.opened = true; it.mesh.visible = false; }
      const g = 10 + Math.floor(Math.random() * 30); inv.add(995, g); const loot = (design.items || []).filter(i => i.id !== 995); const item = loot[Math.floor(Math.random() * loot.length)];
      if (item) inv.add(item.id, 1); say('The chest holds ' + g + ' gold' + (item ? ' and a ' + item.name.toLowerCase() : '') + '.'); SFX.coin(); return true;
    }
    return false;
  }

  // ---- combat ----
  const bolts = [];
  function damageOut() { return P.damage + tree.stat('damage') + Math.max(0, ...inv.slots.filter(Boolean).map(s => itemDef(s.id).damage || 0)); }
  function hurtCreature(k, n) {
    if (k.dead) return; k.hp -= n; k.c.hurt(); k.angry = true; lastTarget = { k, at: performance.now() }; SFX.hit();
    if (k.hp <= 0) {
      k.dead = true; k.deadAt = performance.now(); k.c.die(); SFX.die();
      skills.addXp('level', k.def.xp || 5);
      content.state.vars['kill:' + k.type] = (content.state.vars['kill:' + k.type] | 0) + 1; content.changed();
      const [a, b] = k.def.gold || [0, 0]; const g = a + Math.floor(Math.random() * (b - a + 1)); if (g > 0) { inv.add(995, g); SFX.coin(); }
      const drops = (k.def.drops || []).filter(dr => Math.random() < (dr[2] ?? 1)).map(dr => { inv.add(dr[0], dr[1] || 1); return inv.name(dr[0]); });
      say('You defeat the ' + k.name + '. +' + (k.def.xp || 5) + ' xp' + (g ? ', ' + g + ' gold' : '') + (drops.length ? ', ' + drops.join(', ') : '') + '.');
    }
  }
  function attack() {
    if (player.cool > 0 || player.dead) return false;
    player.cool = 0.6; hero.attack(); SFX.swing();
    const fx = Math.sin(player.heading), fz = Math.cos(player.heading);
    for (const k of creatures) {
      if (k.dead || k.dungeon !== player.dungeon) continue;
      const dx = k.x - player.x, dz = k.z - player.z, d = Math.hypot(dx, dz), reach = 2.2 + (k.c.spec.size > 2 ? k.c.spec.size * 0.3 : 0);
      if (d < reach && (d < 0.8 || (dx * fx + dz * fz) / d > 0.35)) hurtCreature(k, damageOut());
    }
    return true;
  }
  function fireball() {
    if (!tree.has('fireball')) { say('You have not learned Fireball yet (Mage, in the skill tree).'); return false; }
    if (player.spellCool > 0 || player.dead) return false;
    player.spellCool = 1.6; SFX.fire();
    const m = new THREE.Mesh(new THREE.IcosahedronGeometry(0.3), new THREE.MeshBasicMaterial({ color: '#ff7a20' }));
    m.position.set(player.x, player.y + 1.3, player.z); scene.add(m);
    bolts.push({ m, x: player.x, z: player.z, vx: Math.sin(player.heading) * 22, vz: Math.cos(player.heading) * 22, life: 1.4, dmg: 25 + tree.stat('spellPower') });
    return true;
  }
  let lastTarget = null;
  function hurtPlayer(n, who) {
    if (player.dead) return;
    const armor = Math.min(0.8, tree.stat('armor')); const got = Math.max(1, Math.round(n * (1 - armor)));
    player.hp -= got; hero.hurt(); SFX.hurt();
    if (player.hp <= 0) { player.hp = 0; player.dead = 2.5; hero.die(); say('You were killed by the ' + who.name + '. You wake up in ' + startV.name + '.'); }
  }
  function respawn() {
    player.dead = 0; hero.revive(); player.hp = player.maxHp; player.dungeon = null; player.house = null; player.level = 0;
    player.x = startV.x + 4; player.z = startV.z + 4; setArea();
    for (const k of creatures) k.angry = false;
  }

  // ---- menu: skills, quests, bag, options ----
  const menu = document.getElementById('menu'), mbody = document.getElementById('mbody'); let tab = 'skills', reading = null;
  const el = (t, c, s) => { const e = document.createElement(t); if (c) e.className = c; if (s != null) e.textContent = s; return e; };
  function drawMenu() {
    for (const t of document.querySelectorAll('#mtabs div')) t.classList.toggle('on', t.dataset.tab === tab);
    mbody.innerHTML = '';
    if (tab === 'skills') { const box = el('div'); mbody.appendChild(box); tree.mount(box, say);
      mbody.appendChild(el('div', 'qline', 'Level ' + skills.level('level') + ' · ' + Math.floor(skills.xp.level || 0) + ' xp (' + skills.toNext('level') + ' to the next level) · damage ' + damageOut() + ' · health ' + player.maxHp)); }
    if (tab === 'quests') {
      if (reading) { const b = el('div', 'obtn', '‹ Quests'); b.onpointerdown = e => { e.stopPropagation(); reading = null; drawMenu(); }; mbody.appendChild(b);
        mbody.appendChild(el('div', 'qtitle', content.questName(reading))); for (const l of content.journal(reading)) mbody.appendChild(el('div', 'qline' + (l.done ? ' qdone' : ''), l.text)); }
      else { mbody.appendChild(el('div', 'qline', 'Quest points: ' + content.points()));
        for (const q of content.questList) { const st = content.status(q.id), b = el('div', 'quest ' + (st === 'done' ? 'qgreen' : st === 'started' ? 'qyellow' : 'qred'), q.name); b.dataset.quest = q.id; b.onpointerdown = e => { e.stopPropagation(); reading = q.id; drawMenu(); }; mbody.appendChild(b); } }
    }
    if (tab === 'bag') { const g = el('div', 'bag'); mbody.appendChild(g); inv.slots.forEach((s, i) => { if (!s) return; const d = itemDef(s.id), b = el('div', null, inv.name(s.id) + (s.count > 1 ? ' x' + s.count : '') + (d.heal ? ' (tap to drink)' : '') + (d.damage ? ' (+' + d.damage + ' damage)' : ''));
      b.onpointerdown = e => { e.stopPropagation(); if (d.heal) { inv.remove(i, 1); player.hp = Math.min(player.maxHp, player.hp + d.heal); say('You drink the ' + inv.name(s.id).toLowerCase() + '.'); drawMenu(); } }; g.appendChild(b); }); }
    if (tab === 'options') {
      const m = el('div', 'obtn', settings.muted ? 'Sound is off (tap to turn on)' : 'Sound is on (tap to mute)'); m.onpointerdown = e => { e.stopPropagation(); settings.muted = !settings.muted; drawMenu(); }; mbody.appendChild(m);
      const s = el('div', 'obtn', 'Save now'); s.onpointerdown = e => { e.stopPropagation(); saveNow(); say('Game saved.'); }; mbody.appendChild(s);
      let armed = false; const r = el('div', 'obtn', 'Start a new game'); r.onpointerdown = e => { e.stopPropagation(); if (!armed) { armed = true; r.textContent = 'Really erase your progress? Tap again'; return; } save.remove('main'); resetting = true; location.reload(); }; mbody.appendChild(r);
    }
  }
  const toggleMenu = open => { menu.hidden = open === undefined ? !menu.hidden : !open; if (!menu.hidden) drawMenu(); SFX.click(); };
  document.getElementById('menubtn').addEventListener('pointerdown', e => { e.stopPropagation(); toggleMenu(); });
  for (const t of document.querySelectorAll('#mtabs div')) t.addEventListener('pointerdown', e => { e.stopPropagation(); tab = t.dataset.tab; reading = null; drawMenu(); });
  menu.addEventListener('pointerdown', e => e.stopPropagation());
  content.o.onChange = () => { if (!menu.hidden && tab === 'quests') drawMenu(); };

  // ---- saving ----
  let resetting = false;
  const saveNow = () => { if (resetting) return; save.write('main', { pos: { x: player.x, z: player.z, level: player.level, house: player.house, dungeon: player.dungeon }, hp: player.hp, xp: skills.toJSON(), tree: tree.toJSON(), inv: inv.slots, content: content.toJSON(), opened: [...opened] }); };
  setInterval(saveNow, 20000); addEventListener('pagehide', saveNow); document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') saveNow(); });

  // ---- one step of the game ----
  const tmp = new THREE.Vector3(), eye = new THREE.Vector3(), look = new THREE.Vector3(), ray = new THREE.Raycaster(); let auto = false, here = null, placeName = '';
  const groundMesh = scene.getObjectByName('ground');
  function blockersNear() {
    if (player.dungeon != null) return [world.dungeons[player.dungeon].mesh];
    const out = groundMesh ? [groundMesh] : [];
    for (const h of world.houses) if (Math.hypot(h.x - player.x, h.z - player.z) < 22) { for (const m of h.levels) if (m.visible) out.push(m); if (h.roof.visible) out.push(h.roof); }
    return out;
  }
  function step(dt) {
    player.cool = Math.max(0, player.cool - dt); player.spellCool = Math.max(0, player.spellCool - dt);
    if (player.dead) { player.dead -= dt; hero.update(dt, 0); if (player.dead <= 0) respawn(); input.endFrame(); return; }
    const talking = content.talking, menuOpen = !menu.hidden;
    // move relative to the camera
    let a = talking || menuOpen ? { x: 0, y: 0 } : input.axis(), speed = 0;
    if (a.x || a.y) {
      const sp = (P.speed + tree.stat('speed')) * Math.min(1, Math.hypot(a.x, a.y));
      const fx = -Math.sin(yaw), fz = -Math.cos(yaw), rx = Math.cos(yaw), rz = -Math.sin(yaw);
      const mx = (rx * a.x - fx * a.y), mz = (rz * a.x - fz * a.y), len = Math.hypot(mx, mz) || 1;
      const ox = player.x, oz = player.z;
      player.x += mx / len * sp * dt; player.z += mz / len * sp * dt; player.heading = Math.atan2(mx, mz);
      // steep slopes and deep water stop the walk on the surface
      if (player.dungeon == null && player.level === 0) { const h0 = world.heightAt(ox, oz), h1 = world.heightAt(player.x, player.z); if (h1 - h0 > sp * dt * 1.6 || h1 < world.water - 1.2) { player.x = ox; player.z = oz; } }
      world.collide(player, 0.4, where());
      speed = Math.hypot(player.x - ox, player.z - oz) / dt; player.moved += speed * dt;
    }
    // inside a house: which one (the roof and the floors above hide)
    if (player.dungeon == null) { const h = world.houseAt(player.x, player.z); if (player.level === 0) player.house = h ? h.id : null; else if (!h || h.id !== player.house) { player.level = 0; player.house = h ? h.id : null; } }
    player.y += (groundY(player.x, player.z) - player.y) * Math.min(1, dt * 14);
    if (!talking && !menuOpen) {
      if (input.pressed('attack')) attack();
      if (input.pressed('spell')) fireball();
      if (input.pressed('use')) use();
    }
    if (input.pressed('menu')) toggleMenu();
    if (auto) { const t = creatures.filter(k => hostile(k) || k.angry).filter(k => !k.dead && k.dungeon === player.dungeon).sort((p, q) => Math.hypot(p.x - player.x, p.z - player.z) - Math.hypot(q.x - player.x, q.z - player.z))[0];
      if (t && Math.hypot(t.x - player.x, t.z - player.z) < 3) { player.heading = Math.atan2(t.x - player.x, t.z - player.z); attack(); } }
    const regen = tree.stat('regen'); if (regen && player.hp < player.maxHp) player.hp = Math.min(player.maxHp, player.hp + regen * dt);
    hero.group.position.set(player.x, player.y, player.z); hero.group.rotation.y = player.heading; hero.update(dt, speed);

    // creatures: only those near the player think and show
    for (const k of creatures) {
      const same = k.dungeon === player.dungeon && player.level === 0, d = Math.hypot(k.x - player.x, k.z - player.z);
      k.c.group.visible = (same || (k.dungeon === player.dungeon && d < 30)) && d < (player.dungeon != null ? 40 : 110) && !(k.dead && performance.now() - k.deadAt > 3200);
      if (k.dead) { k.c.update(dt, 0); if (!k.def.boss && performance.now() - k.deadAt > 60000) { k.dead = false; k.hp = k.maxHp; k.x = k.home.x; k.z = k.home.z; k.angry = false; k.c.revive(); } continue; }
      if (d > 70) continue;
      let ms = 0, tx = null, tz = null; k.cool = Math.max(0, k.cool - dt);
      const reach = 1.5 + (k.c.spec.size > 2 ? k.c.spec.size * 0.35 : 0);
      if (same && (hostile(k) || k.angry) && d < (k.def.aggro || 12) * (k.angry ? 2 : 1) && !player.dead) {
        if (d > reach) { tx = player.x; tz = player.z; ms = k.def.speed; }
        else { k.heading = Math.atan2(player.x - k.x, player.z - k.z); if (k.cool <= 0 && k.def.damage > 0) { k.cool = k.def.boss ? 1.8 : 1.4; k.c.attack(); setTimeout(() => { if (!k.dead && Math.hypot(k.x - player.x, k.z - player.z) < reach + 0.6) hurtPlayer(k.def.damage, k); }, 250); } }
      } else if (same && k.def.flee && d < 9) { tx = k.x * 2 - player.x; tz = k.z * 2 - player.z; ms = k.def.speed; }
      else { if (!k.wander || Math.random() < 0.004) k.wander = Math.random() < 0.5 ? null : { x: k.home.x + (Math.random() - 0.5) * 16, z: k.home.z + (Math.random() - 0.5) * 16 };
        if (k.wander) { tx = k.wander.x; tz = k.wander.z; ms = k.def.speed * 0.35; if (Math.hypot(tx - k.x, tz - k.z) < 0.5) k.wander = null; } }
      if (tx != null) { const dx = tx - k.x, dz = tz - k.z, l = Math.hypot(dx, dz) || 1; k.x += dx / l * ms * dt; k.z += dz / l * ms * dt; k.heading = Math.atan2(dx, dz);
        if (k.dungeon == null && world.heightAt(k.x, k.z) < world.water) { k.x -= dx / l * ms * dt; k.z -= dz / l * ms * dt; k.wander = null; }
        world.collide(k, 0.35 * Math.max(1, k.c.spec.size * 0.5), { level: 0, dungeon: k.dungeon }); }
      const ky = k.dungeon != null ? 0 : world.heightAt(k.x, k.z);
      k.c.group.position.set(k.x, ky, k.z); k.c.group.rotation.y = k.heading; k.c.update(dt, tx != null ? ms : 0);
    }
    // people: stroll near home, stop and face the player while talking
    for (const p of people) {
      if (p.talking && !content.talking) p.talking = false;
      let ms = 0;
      if (p.talking) p.heading = Math.atan2(player.x - p.x, player.z - p.z);
      else { if (!p.wander && Math.random() < 0.003) p.wander = { x: p.home.x + (Math.random() - 0.5) * 8, z: p.home.z + (Math.random() - 0.5) * 8 };
        if (p.wander) { const dx = p.wander.x - p.x, dz = p.wander.z - p.z, l = Math.hypot(dx, dz); if (l < 0.4) p.wander = null; else { ms = 1.2; p.x += dx / l * ms * dt; p.z += dz / l * ms * dt; p.heading = Math.atan2(dx, dz); world.collide(p, 0.4, { level: 0 }); } } }
      p.c.group.visible = player.dungeon == null && Math.hypot(p.x - player.x, p.z - player.z) < 100;
      p.c.group.position.set(p.x, world.heightAt(p.x, p.z), p.z); p.c.group.rotation.y = p.heading; p.c.update(dt, ms);
    }
    // fireballs
    for (let i = bolts.length - 1; i >= 0; i--) {
      const b = bolts[i]; b.life -= dt; b.x += b.vx * dt; b.z += b.vz * dt; b.m.position.set(b.x, groundY(b.x, b.z, 0, null) + 1.2, b.z);
      const hit = creatures.find(k => !k.dead && k.dungeon === player.dungeon && Math.hypot(k.x - b.x, k.z - b.z) < 0.9 + k.c.spec.size * 0.2);
      if (hit) hurtCreature(hit, b.dmg);
      if (hit || b.life <= 0 || (player.dungeon != null && !world.dungeons[player.dungeon].walkable(b.x, b.z))) { scene.remove(b.m); bolts.splice(i, 1); }
    }
    // houses: hide the roof of the house the player is in, and the floors above
    for (const h of world.houses) { const inside = player.house === h.id && player.dungeon == null; h.roof.visible = !inside; h.levels.forEach((m, k) => { m.visible = !inside || k <= player.level; }); }
    here = talking || menuOpen ? null : nearest();
    placeName = player.dungeon != null ? world.dungeons[player.dungeon].name : (world.villages.find(v => Math.hypot(v.x - player.x, v.z - player.z) < v.radius + 8) || {}).name || 'The wilds';
    // camera: behind the player, closer indoors and underground
    const dist = player.dungeon != null ? 5.5 : player.house != null ? 4.6 : 8, high = player.dungeon != null ? 3.2 : player.house != null ? 3.4 : 4.6;
    tmp.set(player.x + Math.sin(yaw) * dist, player.y + high, player.z + Math.cos(yaw) * dist);
    // the camera stops in front of a wall, roof, hill or dungeon rock between it and the hero
    eye.set(player.x, player.y + 1.4, player.z); look.copy(tmp).sub(eye); const full = look.length(); look.normalize();
    ray.set(eye, look); ray.far = full;
    const hit = ray.intersectObjects(blockersNear(), false)[0];
    if (hit) tmp.copy(eye).addScaledVector(look, Math.max(0.8, hit.distance - 0.35));
    hero.group.visible = !hit || hit.distance > 1.7 || player.dead > 0;   // a wall right behind: hide the hero rather than look through its head
    if (camera.position.distanceTo(tmp) > 25) camera.position.copy(tmp); else camera.position.lerp(tmp, 1 - Math.exp(-(hit ? 14 : 6) * dt));   // a teleport: no long swoop camera.lookAt(player.x, player.y + 1.4, player.z);
    torch.position.set(player.x, player.y + 2.5, player.z);
    input.endFrame();
  }

  function draw() {
    renderer.render(scene, camera);
    const W = hud.width, Hh = hud.height; hc.clearRect(0, 0, W, Hh);
    bar(hc, 12, 12, 170, 14, player.hp / player.maxHp, { color: '#d33', back: '#400' }); text(hc, Math.ceil(player.hp) + ' / ' + player.maxHp, 16, 12, { size: 11 });
    const lv = skills.level('level'), cur = skills.xp.level || 0, lo = XP_AT[lv] || 0, hi = XP_AT[lv + 1] || lo + 1;
    bar(hc, 12, 30, 170, 8, lv >= 99 ? 1 : (cur - lo) / (hi - lo), { color: '#e8c040', back: '#332' });
    text(hc, 'Level ' + lv + (tree.points ? '  ·  ' + tree.points + ' skill point' + (tree.points > 1 ? 's' : '') + ' (≡)' : ''), 12, 42, { size: 12, color: tree.points ? '#ffd870' : '#fff' });
    text(hc, placeName + (player.house != null ? ' (floor ' + (player.level + 1) + ')' : ''), W / 2, 12, { size: 14, align: 'center', bold: true });
    if (lastTarget && performance.now() - lastTarget.at < 5000 && !lastTarget.k.dead) { const k = lastTarget.k; text(hc, k.name, W / 2, 32, { size: 12, align: 'center' }); bar(hc, W / 2 - 60, 48, 120, 8, k.hp / k.maxHp, { color: '#c33', back: '#300' }); }
    const now = performance.now(); log.forEach((m, i) => { const age = now - m.at; if (age < 8000) { hc.globalAlpha = Math.min(1, (8000 - age) / 1000); text(hc, m.t, 12, Hh - 230 - i * 18, { size: 13 }); hc.globalAlpha = 1; } });
    if (here) text(hc, 'E: ' + here.label, W - 180, Hh - 112, { size: 14, color: '#ffe', align: 'center', bold: true });
    for (const b of input.buttons) b.hidden = (b.action === 'spell' && !tree.has('fireball')) || (b.action === 'use' && !here);
    const keep = input.buttons; input.buttons = keep.filter(b => !b.hidden); if (!content.talking && menu.hidden) input.draw(hc); input.buttons = keep;
  }

  // ---- for play tests and the console ----
  const near = (list, f = () => true) => list.filter(f).sort((p, q) => Math.hypot(p.x - player.x, p.z - player.z) - Math.hypot(q.x - player.x, q.z - player.z))[0];
  window.__game = {
    ready: true, design, world, player, creatures, people, tree, content, inv, skills, scene, camera,
    get xp() { return skills.xp.level || 0; }, get here() { return here && here.label; },
    moved: () => player.moved, attack, fireball, use, enterDungeon, leaveDungeon, saveNow,
    teleport(x, z, o = {}) { player.x = x; player.z = z; player.level = o.level | 0; player.house = o.house ?? null; player.dungeon = o.dungeon ?? null; setArea(); return true; },
    // stand next to the nearest interactable of a kind (stairsUp, stairsDown, enterDungeon, leaveDungeon, chest) and face it
    goTo(kind) { const it = near(world.interactables, x => x.kind === kind && (x.inside ? x.dungeon === player.dungeon : player.dungeon == null) && (x.level || 0) === (kind === 'stairsDown' ? Math.max(1, player.level) : player.level));
      if (!it) return false; if (it.house != null) { player.house = it.house; player.level = it.level; } player.x = it.x + 0.3; player.z = it.z + 0.3; return it.label; },
    talkTo(name) { const p = near(people, x => x.name.toLowerCase().includes(String(name).toLowerCase())); if (!p) return false; if (player.dungeon != null) leaveDungeon(); player.x = p.x + 1; player.z = p.z; return use({ kind: 'talk', person: p }); },
    // walk up to the nearest hostile creature (in this area) and keep attacking it
    fightNearest(type) { const k = near(creatures, c => !c.dead && c.dungeon === player.dungeon && (type ? c.type === type : hostile(c))); if (!k) return false; player.x = k.x + 1.2; player.z = k.z; player.heading = Math.atan2(k.x - player.x, k.z - player.z); auto = true; return k.name; },
    stopFighting() { auto = false; }
  };
  window.render_game_to_text = () => JSON.stringify({ place: placeName, x: +player.x.toFixed(1), z: +player.z.toFixed(1), floor: player.level, house: player.house, dungeon: player.dungeon, hp: Math.ceil(player.hp), maxHp: player.maxHp,
    level: skills.level('level'), xp: Math.floor(skills.xp.level || 0), skillPoints: tree.points, skills: [...tree.owned], quests: Object.fromEntries(content.questList.map(q => [q.id, content.status(q.id)])), near: here && here.label,
    creaturesNear: creatures.filter(k => !k.dead && k.dungeon === player.dungeon && Math.hypot(k.x - player.x, k.z - player.z) < 20).map(k => k.name).slice(0, 8), gold: inv.count(995), talking: content.talking });
  say('Welcome to ' + (design.title || 'the realm') + '. Move with the joystick, drag the right side to look, A to attack, E to use.');
  new Loop(step, draw).start();
})();
