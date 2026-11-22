// A game on a map exported from a game cache: walk, fight, chop trees, bank, level up, save. One step per game tick (600 ms).
// The rules and numbers are in rules.js (combat, enemies) and gather.js (trees); the map, pictures and names come from the cache.
// With data/scene (game_cache export kind=scene) the world is drawn in 3D by view3d.js, with animated characters (anim.js);
// otherwise, or when the player picks 2D in Options, it is drawn from above by render.js. The rules are the same either way.
import { World } from './world.js';
import { findPath, nearestWalkable } from './pathfinder.js';
import { MapView, loadGround, loadPlaces, drawMinimap } from './render.js';
import { createGame, CONFIG } from './rules.js';
import { Inventory } from './inv.js';
import { Skills, LABEL, defineSkills } from './skills.js';
import { Content } from './content.js';
import { wallBits } from './world.js';
import { Sfx } from './sfx.js';
import { Save } from './save.js';
import { Gathering, indexNodes, touches } from './gather.js';
import { Bank } from './bank.js';
import { buildPanel } from './ui.js';

const START = { x: 3222, z: 3218 };                // Lumbridge; change to any tile of an exported square
const SQUARES = [];                                 // squares to load: every one around the start
for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) SQUARES.push('data/maps/m' + ((START.x >> 6) + dx) + '_' + ((START.z >> 6) + dz) + '.json');

// animation ids of the player (the same in Old School and 2009 caches): stand, walk, run, punch, kick, block, death, and chopping per axe
const ANIMS = { stand: 808, walk: 819, run: 824, punch: 422, kick: 423, block: 424, death: 836,
  chop: { 1351: 879, 1349: 877, 1353: 875, 1361: 873, 1355: 871, 1357: 869, 1359: 867, 6739: 2846 } };

const canvas = document.getElementById('game'), hud = document.getElementById('hud'), mini = document.getElementById('mini'), chat = document.getElementById('chat');
const lines = [];   // chat box: the newest message is at the top; keeps the last 30
const log = document.createElement('div'); log.id = 'log'; chat.appendChild(log);
function say(text) { lines.unshift(text); lines.length = Math.min(lines.length, 30); log.innerHTML = ''; for (const l of lines.slice(0, 5)) { const d = document.createElement('div'); d.textContent = l; log.appendChild(d); } }
// the side panel takes a slice of the screen on the right; the map fills what is left
const SIDE = () => Math.max(150, Math.min(200, Math.round(innerWidth * 0.3)));
function fit() {
  document.documentElement.style.setProperty('--side', SIDE() + 'px');
  canvas.width = innerWidth - SIDE(); canvas.height = innerHeight;
  if (window.__v3) window.__v3.resize(canvas.width, canvas.height);
  mini.width = mini.height = SIDE() - 16;
}
addEventListener('resize', fit); fit();

async function json(url) { try { const r = await fetch(url); return r.ok ? await r.json() : null; } catch (e) { return null; } }
const picture = url => new Promise(res => { const im = new Image(); im.onload = () => res(im); im.onerror = () => res(null); im.src = url; });

(async () => {
  hud.textContent = 'Loading map...';
  const world = await new World().load(SQUARES);
  if (!world.squares.size) { hud.textContent = 'No map squares found in data/maps. Run game_cache export (kind=maps) first.'; return; }
  const floors = await json('data/floors.json');
  const objs = await json('data/objects.json');
  const names = {}, defs = {}; if (objs) for (const o of objs) { defs[o.id] = o; if (o.name) names[o.id] = o.name; }
  const ground = await loadGround(world);   // shaded ground from data/ground when it was exported, otherwise empty
  const view = new MapView(canvas, world, floors, names, ground);
  const placed = await loadPlaces(world);   // buildings and objects as pictures, where place_objects made them
  view.places = placed.places; view.sprites = placed.sprites;
  view.tile = innerWidth < 700 ? 26 : 34;   // pixels per tile: big enough to tap on a phone
  const playerImg = await picture('data/player.png');   // the player's own picture (game_cache export kind=player), else a plain disc

  // ---- the 3D view, when the scene was baked and the player has not chosen 2D ----
  const sceneIdx = await json('data/scene/index.json');
  let v3 = null, mode3d = false, actorsReady = false;
  const asked = new URLSearchParams(location.search).get('view');   // ?view=2d or ?view=3d overrides the saved choice
  try { mode3d = !!sceneIdx && (asked ? asked === '3d' : localStorage.getItem('view') !== '2d'); } catch (e) { mode3d = !!sceneIdx && asked !== '2d'; }
  if (mode3d) {
    try {
      hud.textContent = 'Loading the 3D world...';
      const { View3D } = await import('./view3d.js');
      const c3 = document.getElementById('game3d');
      v3 = new View3D(c3);
      if (!(await v3.load(sceneIdx.squares))) { v3 = null; }
      else { c3.hidden = false; canvas.classList.add('overlay'); }
    } catch (e) { console.warn('3D view: ' + e.message); v3 = null; }
  }
  hud.textContent = '';

  // ---- the game's own interface pictures (Old School caches: game_cache export kind=sprites into data/sprites) ----
  const uiPic = await picture('data/sprites/1031.png');
  const splatPic = uiPic ? { hit: await picture('data/sprites/1359.png'), miss: await picture('data/sprites/1358.png') } : {};
  if (uiPic) {
    document.getElementById('side').classList.add('sprites');
    for (const id of ['orbhp', 'orbpr', 'runorb']) document.getElementById(id).classList.add('sprites');
    mini.classList.add('round');
    const cp = document.getElementById('compass'); cp.src = 'data/sprites/169.png'; cp.hidden = false;
  }
  // a hit splat: the game's red (damage) or blue (zero) splat with the number on it, else a plain disc
  function splat(g, x, y, amount, size) {
    const im = amount ? splatPic.hit : splatPic.miss;
    if (im) g.drawImage(im, x - size / 2, y - size / 2, size, size);
    else { g.fillStyle = amount ? '#c01818' : '#2a62c8'; g.strokeStyle = '#000'; g.lineWidth = 2; g.beginPath(); g.arc(x, y, size * 0.45, 0, 7); g.fill(); g.stroke(); }
    g.fillStyle = '#fff'; g.font = 'bold ' + Math.round(size * 0.5) + 'px sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle';
    g.strokeStyle = '#000'; g.lineWidth = 3; g.strokeText(String(amount), x, y + 1); g.fillText(String(amount), x, y + 1);
  }

  // ---- the saved game: position, experience, inventory, bank and settings, in one versioned save ----
  const store = new Save('game:' + location.pathname.replace(/[^/]*$/, ''), 1);
  const saved = store.read('main', null) || {};
  let savedAt = saved.at || 0, resetting = false;

  const contentData = await json('data/content.json');   // people, quests, dialogue, actions, recipes (content.js)
  defineSkills(contentData && contentData.skills);         // a game with its own skills lists them there; otherwise the classic ones
  const skills = new Skills(CONFIG.player); skills.load(saved.xp);
  const sfx = new Sfx(saved.settings);
  skills.onLevel = (s, l) => { say('Congratulations, you just advanced a ' + LABEL[s] + ' level. Your ' + LABEL[s] + ' level is now ' + l + '.'); sfx.play('levelup'); panel && panel.refresh(); };

  const inv = new Inventory();
  inv.setNames(await json('data/items.json'));
  inv.mount(document.getElementById('inv'), say);
  if (Array.isArray(saved.inv) && saved.inv.length === inv.slots.length) { inv.slots = saved.inv; inv.render(); }
  else for (const it of CONFIG.startItems) inv.add(it.id, it.count);
  const bank = new Bank({ inv, say, sfx });
  bank.mount(document.getElementById('bank')); bank.load(saved.bank);
  // ---- content: people, dialogue, quests, scripted actions and recipes from data/content.json (content.js) ----
  const content = await new Content({ inv, skills, say, sfx, playerName: CONFIG.playerName || 'Adventurer', fx: (k, i) => fx(k, i),
    heal: n => { game.player.hp = Math.min(game.player.maxHp, game.player.hp + n); stats(); }, teleport: (x, z) => { me.x = me.px = x; me.z = me.pz = z; me.path = []; } }).load('data/content.json', contentData);
  content.loadState(saved.content); content.mount();
  inv.onTap = i => {
    if (bank.isOpen) { bank.deposit(i); sfx.play('coin'); return true; }
    // a selected item tapped on another one: use it on that one (recipes such as a knife on logs, or scripted uses)
    const sel = inv.selected;
    if (sel >= 0 && sel !== i && inv.slots[sel] && inv.slots[i]) {
      const a = inv.slots[sel].id, b = inv.slots[i].id; inv.selected = -1; inv.render();
      if (!content.useOn(a, { item: { id: b, name: inv.name(b) } })) say('Nothing interesting happens.');
      return true;
    }
    return false;
  };
  inv.extraActions = i => { const s = inv.slots[i]; return s ? content.itemActions(s.id).map(a => [a + ' ' + inv.name(s.id), () => content.itemAction(s.id, a)]) : []; };

  // ---- enemies: data/enemies.json and data/npcs/<id>.png from game_cache action=enemies; the rules are in rules.js ----
  const enemyDefs = await json('data/enemies.json');
  const pics = new Map();
  if (enemyDefs) await Promise.all(enemyDefs.map(async d => { const im = await picture('data/npcs/' + d.id + '.png'); if (im) pics.set(d.id, im); }));
  const me = { x: START.x, z: START.z, px: START.x, pz: START.z, path: [], last: performance.now(), run: !!(saved.settings && saved.settings.run), energy: 100 };
  if (saved.pos) { me.x = saved.pos.x; me.z = saved.pos.z; }
  const free = nearestWalkable(world, me.x, me.z); if (free) { me.x = me.px = free.x; me.z = me.pz = free.z; }

  const splats = [];   // damage numbers floating up from whoever was hit
  const fx = (kind, info) => {
    if (kind === 'hit' || kind === 'miss') { sfx.play(kind); splats.push({ who: info.who, amount: info.amount | 0, at: performance.now() }); }
    if (kind === 'loot' && info.items.length) sfx.play('coin');
    if (kind === 'make' && info && info.recipe.anim && v3) seq(info.recipe.anim);                       // the making animation (cooking, fletching...)
    if (kind === 'anim' && v3 && playerActor) seq(info.id).then(() => once(playerActor, info.id));    // a scripted { anim } effect
    if (v3 && actorsReady) act3d(kind, info);
  };
  // shared drop tables (rare drop table, gems, herbs) from game_data export, when the game has server data
  const tables = await json('data/server/drop_tables.json');
  const game = createGame(world, enemyDefs, { x: me.x, z: me.z }, say, inv, CONFIG, { skills, fx, tables });
  if (saved.settings && saved.settings.style) game.player.style = saved.settings.style;
  if (typeof saved.kills === 'number') game.player.kills = saved.kills;

  // ---- trees and bank booths, found in the map by their own "Chop down" and "Bank" actions ----
  // a felled tree disappears from both views (the 3D one empties its triangles until it grows back)
  const treeView = { hide: n => { view.hide(n); if (v3) v3.showObject(+n.lkey.split(',')[0], n.x, n.z, false); }, show: n => { view.show(n); if (v3) v3.showObject(+n.lkey.split(',')[0], n.x, n.z, true); } };
  const gathering = new Gathering({ index: indexNodes(world, defs), skills, inv, say, view: treeView, sfx });
  let pendingBank = null;
  const toward = (x, z) => findPath(world, me.x, me.z, x, z, { near: true }) || [];

  // ---- objects in the map (floor 0) by tile, for scripted actions, recipes ("use raw fish on a range") and doors ----
  const objAt = new Map();
  for (const sq of world.squares.values()) for (const o of sq.json.objects || []) {
    if (o[3] !== 0) continue;
    const d = defs[o[0]]; if (!d || !d.name || d.name === 'null') continue;
    const turned = o[5] & 1, w = o[4] >= 10 ? ((turned ? d.length : d.width) || 1) : 1, l = o[4] >= 10 ? ((turned ? d.width : d.length) || 1) : 1;
    const n = { id: o[0], name: d.name, actions: (d.actions || []).filter(Boolean), x: sq.json.x * 64 + o[1], z: sq.json.z * 64 + o[2], w, l, type: o[4], rot: o[5], lkey: o.slice(0, 4).join(','), plane: 0 };
    for (let i = 0; i < w; i++) for (let j = 0; j < l; j++) { const k = (n.x + i) + ',' + (n.z + j); if (!objAt.has(k)) objAt.set(k, []); objAt.get(k).push(n); }
  }
  // a door is a wall object whose first option is Open: opening it takes its wall away and hides it (it shuts again after a while)
  const isDoor = n => n.type <= 3 && n.actions.some(a => /^open$/i.test(a)) && wallBits(n.type, n.rot);
  const doorsOpen = [];
  function openDoor(n) {
    world.setWallBlocking({ x: n.x, z: n.z, plane: 0, type: n.type, rot: n.rot }, false);
    view.hide(n); if (v3) v3.showObject(n.id, n.x, n.z, false);
    doorsOpen.push({ n, at: Date.now() }); sfx.play('click'); say('You open the ' + n.name.toLowerCase() + '.');
  }
  // the object on a tile that the player most likely means: one the content scripts, then a door, then any with options
  function objectOn(tx, tz, sel) {
    const here = objAt.get(tx + ',' + tz) || [];
    return here.find(n => sel ? content.canUse(sel, 'object', n) : content.actionsFor('object', n).length)
      || here.find(isDoor) || here.find(n => n.actions.length && n.type >= 10) || here.find(n => n.actions.length) || null;
  }

  // ---- people: "people" in data/content.json (game_cache action=enemies writes them from 2009scape's spawns) ----
  const people = (content.people || []).filter(p => p.plane == null || p.plane === 0).map(p => ({ isPerson: true, def: p, id: p.id, name: p.name, x: p.x, z: p.z, px: p.x, pz: p.z, home: { x: p.x, z: p.z }, face: 0 }));
  const personPics = new Map();
  await Promise.all([...new Set(people.map(p => p.id))].map(async id => { const im = await picture('data/npcs/' + id + '.png'); if (im) personPics.set(id, im); }));
  // ---- items lying on the ground ("groundItems" in data/content.json): walk up to one to take it; it comes back after its respawn time ----
  const groundItems = (content.groundItems || []).filter(g => (g.plane | 0) === 0).map(g => ({ ...g, gone: 0 }));
  const iconPics = new Map();
  await Promise.all([...new Set(groundItems.map(g => g.id))].map(async id => { const im = await picture('data/icons/' + id + '.png'); if (im) iconPics.set(id, im); }));
  let pendingAct = null;   // { node(): { x, z, w, l }, run() }: done when the player stands next to it
  const near = (o) => ({ x: o.x, z: o.z, w: o.w || 1, l: o.l || 1 });

  function collect() {
    return { at: Date.now(), pos: { x: me.x, z: me.z }, xp: skills.toJSON(), inv: inv.slots, bank: bank.toJSON(), kills: game.player.kills, content: content.toJSON(), settings: { ...sfx.toJSON(), style: game.player.style, run: me.run } };
  }
  const saveNow = () => { if (resetting) return; if (store.write('main', collect())) savedAt = Date.now(); };
  inv.changed = bank.onChange = () => {};   // the whole game is saved together, every 30 seconds, when the page is hidden, and from Options
  setInterval(saveNow, 30000);
  addEventListener('pagehide', saveNow);
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') saveNow(); });

  const panel = buildPanel({ skills, game, say, sfx, content, sprites: !!uiPic, view3d: sceneIdx ? { on: !!v3, set: on => { try { localStorage.setItem('view', on ? '3d' : '2d'); } catch (e) {} location.reload(); } } : null, save: { now: saveNow, at: () => savedAt, reset: () => { resetting = true; store.remove('main'); location.reload(); } } });
  sfx.onChange = () => panel.refresh();
  if (uiPic) for (const t of document.getElementById('tabs').children) t.classList.add('sprites');

  content.o.onChange = () => { if (panel.current === 'quests') panel.refresh(); };
  window.__game = { game, skills, inv, bank, gathering, sfx, me, view, panel, saveNow, collect, content, people, groundItems, objAt, tapTile: (x, z, p) => tapTile(x, z, p),
    // test helpers for play tests and look_at_preview steps (the assistant cannot find things by screen position in 3D)
    teleport: (x, z) => { me.x = me.px = x; me.z = me.pz = z; me.path = []; return world.ensure ? world.ensure(x, z, 1) : Promise.resolve(); },   // a game that streams squares loads the area
    give: (id, n = 1) => inv.add(id, n),
    messages: (n = 5) => lines.slice(0, n),
    find: name => {
      const re = new RegExp('^' + String(name).replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$', 'i'), seen = new Set(), all = [];
      for (const list of objAt.values()) for (const o of [].concat(list)) if (o && !seen.has(o) && seen.add(o) && re.test(o.name || '')) all.push(o);
      for (const p of people || []) if (re.test(p.name || '')) all.push(p);
      all.sort((a, b) => Math.hypot(a.x - me.x, a.z - me.z) - Math.hypot(b.x - me.x, b.z - me.z));
      const o = all[0]; return o ? { x: o.x, z: o.z, name: o.name, options: (o.actions || []).filter(Boolean) } : null;
    } };
  stats();
  say('Welcome. Tap a tile to walk, an enemy to fight it, a tree to chop it, a bank booth to bank.');
  if (!enemyDefs) say('No enemies yet: run game_cache action=enemies to add some.');
  if (!gathering.index.nodes.length) say('No trees or bank booths found: the map objects need data/objects.json and the map squares need their objects (game_cache action=keys, then export maps again).');

  function stats() {
    document.getElementById('hp').textContent = game.player.hp; document.getElementById('pr').textContent = skills.level('prayer');
    document.getElementById('rn').textContent = Math.round(me.energy); document.getElementById('runorb').classList.toggle('on', me.run);
  }
  document.getElementById('runorb').addEventListener('pointerdown', e => { e.stopPropagation(); me.run = !me.run; say(me.run ? 'You are running.' : 'You are walking.'); stats(); });

  canvas.addEventListener('pointerdown', e => {
    if (v3) return;   // the 3D view takes its own taps (and tells a drag that turns the camera from a tap)
    const r = canvas.getBoundingClientRect();
    tapTile(...view.toTile((e.clientX - r.left) * canvas.width / r.width, (e.clientY - r.top) * canvas.height / r.height), null);
  });
  if (v3) v3.onTap = h => { const ud = h.actor && h.actor.userData, en = ud && ud.enemy, pe = ud && ud.person; if (en) tapTile(en.x, en.z, en); else if (pe) tapTile(pe.x, pe.z, pe); else if (h.tile) tapTile(h.tile[0], h.tile[1], null); };

  // walk up to something and do `run` when next to it
  function approach(node, run) { const n = node(); me.path = touches(me, n) ? [] : toward(n.x, n.z); pendingAct = { node, run }; }

  // a tap on tile (tx, tz): talk to a person, fight the enemy there, chop the tree, use the bank, take an item, use an object, or walk
  function tapTile(tx, tz, picked) {
    gathering.stop(); content.stop(); pendingBank = null; pendingAct = null; bank.close();
    if (content.talking) content.close();
    marker = { x: tx, z: tz, at: performance.now() };
    const sel = inv.selected >= 0 && inv.slots[inv.selected] ? inv.slots[inv.selected].id : 0;
    const clearSel = () => { if (inv.selected >= 0) { inv.selected = -1; inv.render(); } };
    // a person: talk (or use the selected item on them)
    const person = picked && picked.isPerson ? picked : people.find(p => p.x === tx && p.z === tz);
    if (person) {
      game.player.target = null; clearSel();
      approach(() => near(person), () => { person.talking = true; if (sel) { if (!content.useOn(sel, { npc: { id: person.id, name: person.name } })) say('Nothing interesting happens.'); } else content.talkTo(person.def); });
      return;
    }
    const hit = picked && !picked.isPerson && !picked.dead ? picked : game.enemies.find(en => !en.dead && Math.abs(en.x - tx) <= 1 && Math.abs(en.z - tz) <= 1);
    game.player.target = hit || null;
    if (hit) { clearSel(); say('You attack the ' + hit.def.name + '.'); me.path = []; return; }
    // an item on the ground
    const gi = groundItems.find(g => !g.gone && g.x === tx && g.z === tz);
    if (gi && !sel) {
      approach(() => near(gi), () => { if (gi.gone) return; if (inv.add(gi.id, gi.amount || 1)) { say("You don't have enough inventory space."); return; }
        gi.gone = Date.now(); say('You take the ' + (gi.name || inv.name(gi.id)).toLowerCase() + '.'); sfx.play('coin'); if (gi.sprite && v3) { v3.removeSprite(gi.sprite); gi.sprite = null; } });
      return;
    }
    // an object the content knows (use the selected item on it, or its scripted action), or a door
    const obj = objectOn(tx, tz, sel);
    if (obj && (sel || content.actionsFor('object', obj).length || isDoor(obj))) {
      clearSel();
      approach(() => near(obj), () => {
        if (sel) { if (!content.useOn(sel, { object: obj })) say('Nothing interesting happens.'); return; }
        const scripted = content.actionsFor('object', obj);
        if (scripted.length) content.act('object', obj, scripted[0]);
        else if (isDoor(obj)) openDoor(obj);
      });
      return;
    }
    if (sel) { clearSel(); say('Nothing interesting happens.'); }
    const node = gathering.nodeAt(tx, tz);
    if (node) {
      me.path = touches(me, node) ? [] : toward(tx, tz);
      if (node.kind === 'tree') { gathering.start(node); if (gathering.axe()) say('You walk up to the ' + node.name.toLowerCase() + '.'); }
      else { pendingBank = node; say('You walk to the bank booth.'); }
      return;
    }
    const p = findPath(world, me.x, me.z, tx, tz, { near: true });
    me.path = p || [];
    hud.textContent = p ? 'Walking to ' + tx + ', ' + tz + ' (' + p.length + ' steps)' : 'No way there from ' + me.x + ', ' + me.z;
    say(p ? 'You walk to ' + tx + ', ' + tz + '.' : 'You cannot reach that.');
  }
  let marker = null;   // where the player last tapped (a yellow cross in 3D, like the game's)

  // under a roof: the tile's flags say so (bit 4); roofs are hidden while the player stands under one
  function underRoof(x, z) {
    const sq = world.squares.get((x >> 6) + ',' + (z >> 6)), f = sq && sq.json.flags && sq.json.flags[0];
    return !!(f && f.length && (f[(x & 63) * 64 + (z & 63)] & 4));
  }

  let energyTick = 0;
  setInterval(() => { // game tick
    const steps = me.run && me.energy >= 1 ? 2 : 1, moved = me.path.length > 0;
    for (let i = 0; i < steps; i++) { const s = me.path.shift(); if (s) { me.x = s.x; me.z = s.z; me.last = performance.now(); } }
    if (moved && steps === 2) me.energy = Math.max(0, me.energy - 1);
    else if (++energyTick >= 3) { energyTick = 0; me.energy = Math.min(100, me.energy + 1); }
    if (me.energy < 1 && me.run) { me.run = false; say('You run out of energy.'); }
    // fighting: walk up to the target, then let the rules play one tick
    const pl = game.player, tg = pl.target;
    pl.x = me.x; pl.z = me.z;
    if (tg && !tg.dead && Math.max(Math.abs(tg.x - me.x), Math.abs(tg.z - me.z)) > 1) {
      const q = findPath(world, me.x, me.z, tg.x, tg.z, { near: true }) || [];
      if (q.length && q[q.length - 1].x === tg.x && q[q.length - 1].z === tg.z) q.pop();
      const end = q.length ? q[q.length - 1] : me;
      if (Math.max(Math.abs(end.x - tg.x), Math.abs(end.z - tg.z)) > 1) { say("I can't reach that!"); pl.target = null; me.path = []; }   // a wall is in the way
      else me.path = q;
    }
    // chopping: come closer if the path ran out before reaching the tree
    const g = gathering.tick(me);
    if (g === 'walk' && !me.path.length) { const n = gathering.active.node; const q = toward(n.x, n.z); if (q.length) me.path = q; else { say('You cannot reach that.'); gathering.stop(); } }
    // banking: open the bank when next to the booth, close it when the player walks away
    if (pendingBank && touches(me, pendingBank)) { bank.open(pendingBank); pendingBank = null; me.path = []; }
    else if (pendingBank && !me.path.length) { const q = toward(pendingBank.x, pendingBank.z); if (q.length) me.path = q; else { say('You cannot reach that.'); pendingBank = null; } }
    if (bank.isOpen && bank.node && !touches(me, bank.node)) bank.close();
    // walking up to a person, an item or an object: act when next to it (people move, so their spot is looked up each tick)
    if (pendingAct) {
      const n = pendingAct.node();
      if (touches(me, n)) { const r = pendingAct.run; pendingAct = null; me.path = []; r(); }
      else if (!me.path.length) { const q = toward(n.x, n.z); if (q.length) me.path = q; else { say('You cannot reach that.'); pendingAct = null; } }
    }
    // making things (recipes) stops when the player walks off
    if (me.path.length && content.making) content.stop();
    content.tick();
    // people wander near their home tile, and stand still while they talk
    for (const p of people) {
      if (p.talking && !content.talking) p.talking = false;
      if (p.talking || !p.def.walk || Math.random() > 0.12) continue;
      const dx = Math.floor(Math.random() * 3) - 1, dz = Math.floor(Math.random() * 3) - 1, r = typeof p.def.walk === 'number' && p.def.walk > 1 ? p.def.walk : 4;
      const nx = p.x + dx, nz = p.z + dz;
      if ((dx || dz) && Math.abs(nx - p.home.x) <= r && Math.abs(nz - p.home.z) <= r && world.canStep(p.x, p.z, dx, dz) && !(nx === me.x && nz === me.z)) { p.x = nx; p.z = nz; }
    }
    // items on the ground come back after their respawn time (in ticks of 600 ms)
    for (const g of groundItems) if (g.gone && Date.now() - g.gone > (g.respawnTicks || 100) * 600) { g.gone = 0; if (v3 && iconPics.has(g.id)) g.sprite = v3.addSprite('data/icons/' + g.id + '.png', g.x, g.z); }
    const r = game.tick();
    if (r.died) { me.x = me.px = pl.x; me.z = me.pz = pl.z; me.path = []; gathering.stop(); }
    stats();
    const roof = underRoof(me.x, me.z);
    if (roof !== view.roofOver) { view.roofOver = roof; view.invalidate(); if (v3) v3.setRoofHidden(roof); else say(roof ? 'You are under a roof.' : 'You step out into the open.'); }
    hud.textContent = 'Tile ' + me.x + ', ' + me.z + (roof ? '  (under a roof)' : '') + '  ·  combat level ' + game.combat + '  ·  kills ' + pl.kills;
    if (panel.current !== 'inv') panel.refresh();
  }, 600);

  // ---- 3D characters: the player and every enemy as animated models ----
  const seqs = {};
  const seq = async id => { if (id == null || id < 0) return null; if (!(id in seqs)) seqs[id] = await loadSeqFn(id); return seqs[id]; };
  let loadSeqFn = null, playerActor = null;
  const actorOf = new Map();
  if (v3) {
    window.__v3 = v3; fit();
    ({ loadSeq: loadSeqFn } = await import('./anim.js'));
    const pj = await json('data/models/player.json');
    if (pj) { playerActor = v3.addActor(pj, { size: 1 }); playerActor.anims = { stand: ANIMS.stand, walk: ANIMS.walk, run: ANIMS.run }; }
    const npcModels = {};
    for (const en of game.enemies) {
      if (!(en.def.id in npcModels)) npcModels[en.def.id] = await json('data/npcs3d/' + en.def.id + '.json');
      const mj = npcModels[en.def.id]; if (!mj) continue;
      const a = v3.addActor(mj, { size: mj.size || en.def.size || 1, scaleXZ: mj.scaleXZ, scaleY: mj.scaleY, userData: { enemy: en } });
      a.anims = { stand: mj.standAnim, walk: mj.walkAnim, ...(mj.anims || {}), ...(en.def.anims || {}) };   // server data wins over names
      en.px = en.x; en.pz = en.z; en.face = 0; actorOf.set(en, a);
    }
    // people as models too (their own stand and walk animations)
    for (const p of people) {
      if (!(p.id in npcModels)) npcModels[p.id] = await json('data/npcs3d/' + p.id + '.json');
      const mj = npcModels[p.id]; if (!mj) continue;
      const a = v3.addActor(mj, { size: mj.size || 1, scaleXZ: mj.scaleXZ, scaleY: mj.scaleY, userData: { person: p } });
      a.anims = { stand: mj.standAnim, walk: mj.walkAnim }; p.actor = a;
    }
    for (const gi of groundItems) if (iconPics.has(gi.id)) gi.sprite = v3.addSprite('data/icons/' + gi.id + '.png', gi.x, gi.z);
    for (const a of [playerActor, ...actorOf.values(), ...people.map(p => p.actor)]) if (a) for (const k of Object.keys(a.anims)) await seq(a.anims[k]);
    for (const k of ['punch', 'kick', 'block', 'death']) await seq(ANIMS[k]);
    for (const id of Object.values(ANIMS.chop)) await seq(id);
    actorsReady = true;
    window.__game.v3 = v3; window.__game.playerActor = playerActor; window.__game.actorOf = actorOf;
  }
  // one-off animations (a swing, a block, a death) play over the walk and stand; afterwards the actor goes back to them
  function once(a, id, then) { if (!a || !seqs[id]) return; a.busyUntil = performance.now() + 2400; a.model.play(seqs[id], { loop: false, restart: true, onEnd: () => { a.busyUntil = 0; if (then) then(); } }); }
  function act3d(kind, info) {
    const w = info && info.who, a = w === game.player ? playerActor : actorOf.get(w);
    if (kind === 'swing') { if (w === game.player) once(a, game.player.style === 'aggressive' ? ANIMS.kick : ANIMS.punch); else if (a && a.anims.attack) once(a, a.anims.attack); }
    if (kind === 'hit' && a && w !== game.player && a.anims.defend && !a.busyUntil) once(a, a.anims.defend);
    if (kind === 'hit' && w === game.player && playerActor && !playerActor.busyUntil) once(playerActor, ANIMS.block);
    if (kind === 'death' && a) { if (w === game.player) once(a, ANIMS.death); else if (a.anims.death) once(a, a.anims.death, () => { a.holder.visible = false; }); else a.holder.visible = false; }
  }
  // the item in the player's hand (an axe while chopping), from data/models/held_<id>.json
  const heldModels = {}; let holding = 0;
  function holdItem(id) {
    if (!playerActor || id === holding) return;
    holding = id;
    if (!id) { v3.hold(playerActor, null); return; }
    if (id in heldModels) { v3.hold(playerActor, heldModels[id]); return; }
    heldModels[id] = null;
    json('data/models/held_' + id + '.json').then(j => { heldModels[id] = j; if (holding === id) v3.hold(playerActor, j); });
  }
  // the right looping animation for an actor: chopping, running, walking or standing
  function base(a, moving, running, chopId) {
    if (!a || a.busyUntil > performance.now()) return;
    const id = chopId || (moving ? (running && a.anims.run ? a.anims.run : a.anims.walk) : a.anims.stand);
    if (seqs[id]) a.model.play(seqs[id]); else if (!moving) a.model.play(null);
  }
  let lastT = performance.now(), meFace = 0;
  const ov = canvas.getContext('2d');

  // glide towards a tile at the game's pace: one tile per 600 ms tick walking, two running (frame-rate independent)
  const glide = (o, tx, tz, tilesPerTick, dt) => {
    const dx = tx - o.px, dz = tz - o.pz, d = Math.hypot(dx, dz), step = tilesPerTick * dt / 600;
    if (d > 3) { o.px = tx; o.pz = tz; return; }            // a teleport (death, a long jump): no slide across the map
    if (d <= step) { o.px = tx; o.pz = tz; } else { o.px += dx / d * step; o.pz += dz / d * step; }
  };
  let lastFrame = performance.now();
  (function frame(now) {
    const fdt = Math.min(200, now - lastFrame); lastFrame = now;
    glide(me, me.x, me.z, me.run ? 2.05 : 1.05, fdt);
    view.cx = me.px + 0.5; view.cz = me.pz + 0.5;
    drawMinimap(mini, view, me);
    if (v3) { draw3d(now); requestAnimationFrame(frame); return; }
    view.draw((g, v) => {
      const t = v.tile;
      if (me.path.length) {
        g.strokeStyle = 'rgba(255,255,255,0.6)'; g.lineWidth = 2; g.beginPath();
        let [sx, sy] = v.toScreen(me.x, me.z); g.moveTo(sx + t / 2, sy + t / 2);
        for (const s of me.path) { [sx, sy] = v.toScreen(s.x, s.z); g.lineTo(sx + t / 2, sy + t / 2); }
        g.stroke();
      }
      for (const gi of groundItems) {
        if (gi.gone) continue;
        const [gx, gy] = v.toScreen(gi.x, gi.z), im = iconPics.get(gi.id);
        if (im) g.drawImage(im, gx + t * 0.1, gy + t * 0.1, t * 0.8, t * 0.8); else { g.fillStyle = '#e8d070'; g.fillRect(gx + t * 0.3, gy + t * 0.3, t * 0.4, t * 0.4); }
      }
      for (const p of people) {
        const [qx, qy] = v.toScreen(p.x, p.z), im = personPics.get(p.id);
        if (im) g.drawImage(im, qx + t / 2 - t, qy + t / 2 - 1.8 * t, 2 * t, 2 * t); else { g.fillStyle = '#e0c040'; g.beginPath(); g.arc(qx + t / 2, qy + t / 2, t * 0.35, 0, 7); g.fill(); }
      }
      for (const en of game.enemies) {
        if (en.dead) continue;
        const [ex, ey] = v.toScreen(en.x, en.z), im = pics.get(en.def.id);
        const k = Math.max(1, Math.min(3, en.def.size | 0 || 1));   // the picture is 2 tiles wide for each tile of size; feet at the tile's centre
        if (im) g.drawImage(im, ex + t / 2 - t * k, ey + t / 2 - 1.8 * t * k, 2 * t * k, 2 * t * k); else { g.fillStyle = '#b22'; g.fillRect(ex + 2, ey + 2, t - 4, t - 4); }
        if (en.hp < en.maxHp) { g.fillStyle = '#a00'; g.fillRect(ex, ey - t * 0.3, t, 4); g.fillStyle = '#0c0'; g.fillRect(ex, ey - t * 0.3, t * Math.max(0, en.hp) / en.maxHp, 4); }
        if (en === game.player.target) { g.strokeStyle = '#ff0'; g.lineWidth = 2; g.strokeRect(ex, ey, t, t); }
      }
      const [px, py] = v.toScreen(me.px, me.pz);
      if (playerImg) g.drawImage(playerImg, px - t * 0.5, py + t / 2 - 1.8 * t, 2 * t, 2 * t);
      else { g.fillStyle = '#fff'; g.strokeStyle = '#000'; g.lineWidth = 2; g.beginPath(); g.arc(px + t / 2, py + t / 2, t * 0.4, 0, 7); g.fill(); g.stroke(); }
      if (game.player.hp < game.player.maxHp) { g.fillStyle = '#a00'; g.fillRect(px, py - t * 0.3, t, 4); g.fillStyle = '#0c0'; g.fillRect(px, py - t * 0.3, t * game.player.hp / game.player.maxHp, 4); }
      // damage numbers: red for a hit, blue for a miss, rising for one second
      for (let i = splats.length - 1; i >= 0; i--) {
        const s = splats[i], age = now - s.at; if (age > 1000) { splats.splice(i, 1); continue; }
        const [sx, sy] = s.who === game.player ? [px, py] : v.toScreen(s.who.x, s.who.z), cx = sx + t / 2, cy = sy - t * 0.2 - age * 0.012 * t / 20;
        splat(g, cx, cy, s.amount, t * 0.8);
      }
      g.textAlign = 'start'; g.textBaseline = 'alphabetic';
    });
    requestAnimationFrame(frame);
  })(performance.now());

  // ---- one 3D frame: move and animate the actors, draw the world, then hit splats and health bars on top ----
  function draw3d(now) {
    const dt = Math.min(100, now - lastT); lastT = now;
    const dx = me.x - me.px, dz = me.z - me.pz, moving = Math.hypot(dx, dz) > 0.01 || me.path.length > 0;
    const tg = game.player.target;
    if (moving && Math.hypot(dx, dz) > 0.01) meFace = v3.constructor.facing(dx, dz);
    else if (tg && !tg.dead) meFace = v3.constructor.facing(tg.x - me.x, tg.z - me.z);
    else if (gathering.active) { const n = gathering.active.node; meFace = v3.constructor.facing(n.x + n.w / 2 - 0.5 - me.x, n.z + n.l / 2 - 0.5 - me.z); }
    if (playerActor) {
      v3.placeActor(playerActor, me.px, me.pz, meFace);
      const axe = gathering.active && !moving ? gathering.axe() : null;
      holdItem(axe ? axe[0] : 0);
      base(playerActor, moving, me.run, axe ? ANIMS.chop[axe[0]] : (content.making && content.making.r.anim) || 0);
    }
    for (const en of game.enemies) {
      const a = actorOf.get(en); if (!a) continue;
      if (en.dead) { if (!a.busyUntil) a.holder.visible = false; continue; }
      if (!a.holder.visible) { a.holder.visible = true; en.px = en.x; en.pz = en.z; a.busyUntil = 0; }
      const ex = en.x - en.px, ez = en.z - en.pz, mv = Math.hypot(ex, ez) > 0.01;
      glide(en, en.x, en.z, 1.05, dt);
      if (mv) en.face = v3.constructor.facing(ex, ez);
      else if (game.player.target === en || Math.max(Math.abs(en.x - me.x), Math.abs(en.z - me.z)) <= 1) en.face = v3.constructor.facing(me.x - en.x, me.z - en.z);
      v3.placeActor(a, en.px, en.pz, en.face);
      base(a, mv, false, 0);
    }
    for (const p of people) {
      const a = p.actor; if (!a) continue;
      const ex = p.x - p.px, ez = p.z - p.pz, mv = Math.hypot(ex, ez) > 0.01;
      glide(p, p.x, p.z, 1.05, dt);
      if (mv) p.face = v3.constructor.facing(ex, ez); else if (p.talking) p.face = v3.constructor.facing(me.x - p.x, me.z - p.z);
      v3.placeActor(a, p.px, p.pz, p.face);
      base(a, mv, false, 0);
    }
    v3.updateChunks(me.px, me.pz);
    // the minimap and compass turn with the camera, as in the game
    const turn = 'rotate(' + (-v3.yaw) + 'rad)'; mini.style.transform = turn; const cp = document.getElementById('compass'); if (cp) cp.style.transform = turn;
    v3.render(dt, me.px + 0.5, me.pz + 0.5);
    // the overlay: tap marker, health bars and hit splats, drawn where the 3D points land on the screen
    ov.clearRect(0, 0, canvas.width, canvas.height);
    const t = 22;
    if (marker && now - marker.at < 600) { const p = v3.project(marker.x + 0.5, marker.z + 0.5); if (p) { ov.strokeStyle = '#ff0'; ov.lineWidth = 3; ov.beginPath(); ov.moveTo(p[0] - 6, p[1] - 6); ov.lineTo(p[0] + 6, p[1] + 6); ov.moveTo(p[0] + 6, p[1] - 6); ov.lineTo(p[0] - 6, p[1] + 6); ov.stroke(); } }
    const bar = (who, x, z, k) => {
      if (who.hp >= who.maxHp) return; const p = v3.project(x + k / 2, z + k / 2, 2.2 * Math.min(2, k)); if (!p) return;
      ov.fillStyle = '#a00'; ov.fillRect(p[0] - 15, p[1], 30, 5); ov.fillStyle = '#0c0'; ov.fillRect(p[0] - 15, p[1], 30 * Math.max(0, who.hp) / who.maxHp, 5);
    };
    bar(game.player, me.px, me.pz, 1);
    for (const en of game.enemies) if (!en.dead) bar(en, en.px, en.pz, en.def.size || 1);
    for (let i = splats.length - 1; i >= 0; i--) {
      const s = splats[i], age = now - s.at; if (age > 1000) { splats.splice(i, 1); continue; }
      const w = s.who, k = w === game.player ? 1 : (w.def.size || 1);
      const p = v3.project((w === game.player ? me.px : w.px ?? w.x) + k / 2, (w === game.player ? me.pz : w.pz ?? w.z) + k / 2, 1.2 * Math.min(2, k)); if (!p) continue;
      const cy = p[1] - age * 0.02;
      splat(ov, p[0], cy, s.amount, t * 1.1);
    }
    ov.textAlign = 'start'; ov.textBaseline = 'alphabetic';
  }
})();
