// The game's rules in one place: the player's levels, the enemies and how they fight. Edit CONFIG to change the game;
// the map, ground, buildings and pictures come from the cache and do not change. Written for PocketIDE's game kit; MIT licensed.
// Enemies come from data/enemies.json. When it was made from server data (game_data export, then game_cache action=enemies
// spawns=...), each enemy has its real stats, attack speed, spawn points, respawn time and drops, and is placed where it stands
// in the real game; otherwise its hitpoints and hits are estimates and it is placed at random round the start.

export const CONFIG = {
  enemyCount: 20,          // how many enemies stand about at the start
  seed: 1,                 // same seed, same places
  spawnRadius: 24,         // tiles round the start where enemies may stand
  keepAway: 8,             // no enemy starts closer than this to the player
  aggroRange: 6,           // enemies chase a player closer than this
  attackEvery: 4,          // ticks between hits (4 ticks = 2.4 seconds, like a normal weapon)
  respawnTicks: 60,        // ticks until a killed enemy returns
  player: { attack: 85, strength: 85, defence: 75, hitpoints: 77, prayer: 55 },   // combat level 100
  startItems: [{ id: 995, count: 1000 }, { id: 1351, count: 1 }],     // coins and a bronze axe (trees need one)
  dropCoins: [5, 40],      // coins an enemy without a drop table drops, between these
  maxEnemies: 80,          // at most this many enemies from real spawn points (the nearest to the start first)
  wander: 4                // how far a walking enemy strays from its spawn point, in tiles
};

// The official formula for combat level.
export function combatLevel(l) {
  const base = 0.25 * (l.defence + l.hitpoints + Math.floor(l.prayer / 2));
  const melee = 0.325 * (l.attack + l.strength);
  return Math.floor(base + melee + 1e-9);
}

// Small seeded random numbers, so a seed gives the same game every time.
export function rng(seed) {
  let s = (seed >>> 0) || 1;
  return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
}

// ext: { skills (the player's Skills; without it the levels in CONFIG stay fixed), fx(kind, info) (called for 'hit', 'miss' and 'death', for sounds and damage numbers) }
// ext.tables: shared drop tables (data/server/drop_tables.json) for drops that name one ("rare drop table", "gem table"...)
export function createGame(world, enemyDefs, start, say, inv, config = CONFIG, ext = {}) {
  const fx = ext.fx || (() => {});
  const lvl = n => ext.skills ? ext.skills.level(n) : config.player[n];
  const rand = rng(config.seed);
  const maxHp0 = lvl('hitpoints'), maxHp = maxHp0;
  const player = { x: start.x, z: start.z, hp: maxHp, maxHp, target: null, cooldown: 0, kills: 0, style: 'accurate', regen: 0 };
  // fighting styles (unarmed): accurate trains Attack, aggressive Strength, defensive Defence; each adds 3 to that level for the fight
  const STYLE_SKILL = { accurate: 'attack', aggressive: 'strength', defensive: 'defence' };
  const enemies = [];
  const near = (x, z) => Math.abs(x - start.x) < config.keepAway && Math.abs(z - start.z) < config.keepAway;
  const pickTile = () => {
    for (let i = 0; i < 400; i++) {
      const x = start.x + Math.round((rand() * 2 - 1) * config.spawnRadius), z = start.z + Math.round((rand() * 2 - 1) * config.spawnRadius);
      if (world.walkable(x, z) && !near(x, z) && !enemies.some(e => e.x === x && e.z === z)) return { x, z };
    }
    return null;
  };
  const real = enemyDefs && enemyDefs.some(d => Array.isArray(d.spawns) && d.spawns.length);
  if (real) {
    // real spawn points (plane 0), nearest to the start first
    const all = [];
    for (const def of enemyDefs) for (const s of def.spawns || []) if ((s[2] || 0) === 0 && world.walkable(s[0], s[1])) all.push({ def, x: s[0], z: s[1], walks: s[3] === 1 });
    all.sort((a, b) => Math.hypot(a.x - start.x, a.z - start.z) - Math.hypot(b.x - start.x, b.z - start.z));
    for (const p of all.slice(0, config.maxEnemies)) enemies.push({ def: p.def, x: p.x, z: p.z, home: { x: p.x, z: p.z }, walks: p.walks, hp: p.def.hitpoints, maxHp: p.def.hitpoints, cooldown: 0, dead: 0 });
  } else if (enemyDefs && enemyDefs.length) for (let i = 0; i < config.enemyCount; i++) {
    const def = enemyDefs[i % enemyDefs.length], at = pickTile(); if (!at) break;
    enemies.push({ def, x: at.x, z: at.z, home: at, walks: false, hp: def.hitpoints, maxHp: def.hitpoints, cooldown: 0, dead: 0 });
  }
  const defLevel = d => d.defence != null ? d.defence : d.combatLevel;
  const attLevel = d => d.attack != null ? d.attack : d.combatLevel;
  const speed = d => d.attackSpeed || config.attackEvery;
  const chasing = d => real ? d.aggressive : true;   // without server data every enemy chases, as before

  // one weighted roll in a list of { id, min, max, weight, table }; id 0 means nothing
  const roll = list => {
    const total = list.reduce((n, x) => n + x.weight, 0); if (!(total > 0)) return null;
    let r = rand() * total;
    for (const x of list) { r -= x.weight; if (r <= 0) return x; }
    return list[list.length - 1];
  };
  const amount = x => x.min + Math.floor(rand() * (x.max - x.min + 1));
  // what a killed enemy leaves: its 'always' drops, one roll of its main table (a shared table is rolled in turn), or coins
  function loot(def) {
    if (!def.drops) { const c = config.dropCoins; return [{ id: 995, name: 'Coins', count: c[0] + Math.floor(rand() * (c[1] - c[0] + 1)) }]; }
    const got = [];
    const take = x => {
      if (!x || !x.id) return;
      if (x.table) { const t = ext.tables && ext.tables[x.table]; if (t) take(roll(t)); return; }
      got.push({ id: x.id, name: x.name, count: amount(x) });
    };
    for (const x of def.drops.always || []) take(x);
    if (def.drops.main) take(roll(def.drops.main));
    if (def.drops.tertiary) for (const x of def.drops.tertiary) if (rand() * 100 < x.weight) take(x);
    return got;
  }
  // The game's own hit chance and maximum hit, without gear bonuses (so they are close, not exact): effective level = level + 8.
  const hitChance = (attack, defence) => {
    const a = attack + 8, d = defence + 8;
    return a > d ? 1 - (d + 2) / (2 * (a + 1)) : a / (2 * (d + 1));
  };
  const maxHit = strength => Math.floor(0.5 + (strength + 8) * 0.1);   // (effective strength x 64 / 640)
  const playerMaxHit = () => maxHit(lvl('strength') + (player.style === 'aggressive' ? 3 : 0));

  // one step towards a tile, if the step is allowed
  const stepToward = (e, tx, tz) => {
    const dx = Math.sign(tx - e.x), dz = Math.sign(tz - e.z);
    for (const [sx, sz] of [[dx, dz], [dx, 0], [0, dz]]) {
      if ((sx || sz) && world.canStep(e.x, e.z, sx, sz) && !enemies.some(o => o !== e && !o.dead && o.x === e.x + sx && o.z === e.z + sz) && !(e.x + sx === player.x && e.z + sz === player.z)) { e.x += sx; e.z += sz; return; }
    }
  };
  const adjacent = (a, b) => Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z)) <= 1;

  function tick() {
    if (player.cooldown > 0) player.cooldown--;
    player.maxHp = lvl('hitpoints');
    if (++player.regen >= 100) { player.regen = 0; if (player.hp > 0 && player.hp < player.maxHp) player.hp++; }   // one hitpoint every 100 ticks
    // the player hits the chosen enemy when next to it
    const t = player.target;
    if (t && !t.dead && adjacent(player, t) && player.cooldown === 0) {
      player.cooldown = config.attackEvery;
      fx('swing', { who: player, target: t });
      if (rand() < hitChance(lvl('attack') + (player.style === 'accurate' ? 3 : 0), defLevel(t.def))) {
        const hit = Math.floor(rand() * (playerMaxHit() + 1)); t.hp -= hit;
        say(hit ? 'You hit the ' + t.def.name + ' for ' + hit + '.' : 'You miss the ' + t.def.name + '.');
        fx(hit ? 'hit' : 'miss', { who: t, amount: hit });
        if (hit && ext.skills) { ext.skills.addXp(STYLE_SKILL[player.style], 4 * hit); ext.skills.addXp('hitpoints', 1.33 * hit); }   // 4 xp for each point of damage, and 1.33 Hitpoints
        if (t.hp <= 0) {
          t.dead = t.def.respawnTicks || config.respawnTicks; player.kills++; player.target = null;
          const got = loot(t.def);
          for (const g of got) if (inv) inv.add(g.id, g.count);
          say('You kill the ' + t.def.name + '.' + (got.length ? ' It drops ' + got.map(g => (g.count > 1 ? g.count + ' x ' : '') + g.name).join(', ') + '.' : ''));
          fx('loot', { who: t, items: got });
          fx('death', { who: t });
        }
      } else { say('You miss the ' + t.def.name + '.'); fx('miss', { who: t, amount: 0 }); }
    }
    for (const e of enemies) {
      if (e.dead) { if (--e.dead === 0) { e.x = e.home.x; e.z = e.home.z; e.hp = e.maxHp; } continue; }
      if (e.cooldown > 0) e.cooldown--;
      const dist = Math.max(Math.abs(e.x - player.x), Math.abs(e.z - player.z));
      const engaged = player.target === e;
      if (adjacent(e, player) && (engaged || chasing(e.def))) {
        if (e.cooldown === 0) {
          e.cooldown = speed(e.def);
          fx('swing', { who: e, target: player });
          if (rand() < hitChance(attLevel(e.def), lvl('defence') + (player.style === 'defensive' ? 3 : 0))) {
            const hit = Math.floor(rand() * (e.def.maxHit + 1)); player.hp = Math.max(0, player.hp - hit);   // enemy max hit is estimated from its level
            say(hit ? 'The ' + e.def.name + ' hits you for ' + hit + '.' : 'The ' + e.def.name + ' misses you.');
            fx(hit ? 'hit' : 'miss', { who: player, amount: hit });
          } else { say('The ' + e.def.name + ' misses you.'); fx('miss', { who: player, amount: 0 }); }
        }
        if (!player.target && !e.dead) player.target = e;   // fight back
      } else if (engaged ? dist <= 16 : (chasing(e.def) && dist <= (e.def.aggroRadius || config.aggroRange))) stepToward(e, player.x, player.z);
      else if (e.walks && rand() < 0.1) {   // idle NPCs stroll about near where they stand
        const tx = e.home.x + Math.round((rand() * 2 - 1) * config.wander), tz = e.home.z + Math.round((rand() * 2 - 1) * config.wander);
        stepToward(e, tx, tz);
      }
    }
    if (player.hp <= 0) { say('Oh dear, you are dead!'); fx('death', { who: player }); player.x = start.x; player.z = start.z; player.hp = player.maxHp; player.target = null; return { died: true }; }
    return {};
  }
  const levels = () => ({ attack: lvl('attack'), strength: lvl('strength'), defence: lvl('defence'), hitpoints: lvl('hitpoints'), prayer: lvl('prayer') });
  return { player, enemies, tick, get combat() { return combatLevel(levels()); }, config };
}
