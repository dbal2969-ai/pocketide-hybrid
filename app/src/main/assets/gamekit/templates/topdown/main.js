// Top-down tile game: walk with the joystick/arrow keys or tap a tile to path-find there, talk to the villager, collect the coins.
// Change ROWS and LEGEND to make your own level, or load a map file made by game_assets with TileMap.load('data/map.json').
import { Loop } from './core/loop.js';
import { Input } from './core/input.js';
import { TileMap } from './core/tilemap.js';
import { Camera } from './core/camera.js';
import { moveBox, overlap } from './core/grid.js';
import { findPath } from './core/pathfinder.js';
import { Entities } from './core/entities.js';
import { Dialogue, text } from './core/ui.js';
import { Save } from './core/save.js';
import { Sound } from './core/sound.js';

const ROWS = [
  '##############################',
  '#............................#',
  '#..TT....c.........~~~.......#',
  '#..TT..............~~~...c...#',
  '#.......###........~~~.......#',
  '#...c...#.#..................#',
  '#.......#.#........n.........#',
  '#.......#.#..................#',
  '#...........TT.......c.......#',
  '#...p.......TT...............#',
  '#............................#',
  '#....c.................TT....#',
  '#......................TT....#',
  '##############################',
];
const LEGEND = {
  '#': { tile: 1, solid: true }, '.': { tile: 2 }, 'T': { tile: 3, solid: true }, '~': { tile: 4, solid: true },
  'c': { tile: 2 }, 'n': { tile: 2 }, 'p': { tile: 2 },
};
const map = TileMap.fromText(ROWS, LEGEND, 16, { c: 'coin', n: 'npc', p: 'player' });
const ts = map.tileSize;

const canvas = document.getElementById('game'), ctx = canvas.getContext('2d');
const cam = new Camera(1, 1); cam.setBounds(map.width * ts, map.height * ts);
function fit() { canvas.width = innerWidth; canvas.height = innerHeight; cam.resize(canvas.width, canvas.height); cam.zoom = Math.max(2, Math.min(5, canvas.width / (12 * ts))); }
addEventListener('resize', fit); fit();

const input = new Input(canvas);
input.bind('talk', ['Space', 'KeyE', 'Enter']);
input.addButton('talk', 'A', (w, h) => [w - 60, h - 70], 34);
const sound = new Sound(); sound.load('coin', 'assets/coin.wav'); sound.load('talk', 'assets/blip.wav');
const save = new Save('topdown');
const dialogue = new Dialogue();
const world = new Entities();

const start = map.objectsOf('player')[0] || { x: 1, y: 1 };
const player = world.add({ kind: 'player', x: start.x * ts + 3, y: start.y * ts + 3, w: 10, h: 10 });
map.objectsOf('coin').forEach((o, i) => world.add({ kind: 'coin', n: i, x: o.x * ts + 4, y: o.y * ts + 4, w: 8, h: 8 }));
const npc = (() => { const o = map.objectsOf('npc')[0]; return o ? world.add({ kind: 'npc', x: o.x * ts + 2, y: o.y * ts + 2, w: 12, h: 12 }) : null; })();
const totalCoins = world.count('coin');
const saved = save.read('main', null);
if (saved) { player.x = saved.x; player.y = saved.y; world.kind('coin').forEach(c => { if (saved.got.includes(c.n)) world.remove(c); }); world.sweep(); }
let score = totalCoins - world.count('coin'), path = [], told = false;

function persist() { save.write('main', { x: player.x, y: player.y, got: world.list.filter(e => e.kind === 'coin' && !e.alive).map(e => e.n).concat(saved ? saved.got : []) }); }

function update(dt) {
  dialogue.update(dt);
  const taps = input.takeTaps();
  for (const t of taps) {
    if (dialogue.tap()) continue;
    const w = cam.toWorld(t.x, t.y);
    const p = findPath(map.grid, Math.floor((player.x + 5) / ts), Math.floor((player.y + 5) / ts), Math.floor(w.x / ts), Math.floor(w.y / ts), { near: true, radius: 40 });
    path = p || [];
  }
  if (input.pressed('talk') && !dialogue.tap() && npc && Math.hypot(npc.x - player.x, npc.y - player.y) < 28) {
    sound.play('talk');
    dialogue.say(score >= totalCoins ? ['You found every coin! Well done.'] : ['Hello, traveller!', 'There are ' + (totalCoins - score) + ' coins left to find in this valley.'], 'Villager');
    told = true;
  }
  if (!dialogue.active) {
    const a = input.axis(); let mx = a.x, my = a.y;
    if (mx || my) path = [];
    else if (path.length) {
      const goal = path[0], gx = goal.x * ts + ts / 2 - (player.x + 5), gy = goal.z * ts + ts / 2 - (player.y + 5), d = Math.hypot(gx, gy);
      if (d < 3) path.shift(); else { mx = gx / d; my = gy / d; }
    }
    const sp = 70 * dt;
    moveBox(player, mx * sp, 0, (x, y) => map.solid(x, y), ts);
    moveBox(player, 0, my * sp, (x, y) => map.solid(x, y), ts);
  }
  for (const c of world.kind('coin')) if (overlap(player, c)) { world.remove(c); score++; sound.play('coin'); persist(); if (score === totalCoins) dialogue.say(['That was the last coin! Talk to the villager.'], 'You'); }
  world.sweep();
  cam.follow(player.x + 5, player.y + 5, dt);
  input.endFrame();
}

function render() {
  ctx.fillStyle = '#10151c'; ctx.fillRect(0, 0, canvas.width, canvas.height);
  cam.begin(ctx);
  map.draw(ctx, cam);
  for (const c of world.kind('coin')) { ctx.fillStyle = '#fd3'; ctx.beginPath(); ctx.arc(c.x + 4, c.y + 4, 4, 0, 7); ctx.fill(); }
  if (npc) { ctx.fillStyle = '#6af'; ctx.fillRect(npc.x, npc.y, npc.w, npc.h); ctx.fillStyle = '#fff'; ctx.fillRect(npc.x + 3, npc.y + 3, 2, 2); ctx.fillRect(npc.x + 7, npc.y + 3, 2, 2); }
  if (path.length) { ctx.strokeStyle = 'rgba(255,255,255,0.5)'; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(player.x + 5, player.y + 5); for (const s of path) ctx.lineTo(s.x * ts + ts / 2, s.z * ts + ts / 2); ctx.stroke(); }
  ctx.fillStyle = '#fff'; ctx.fillRect(player.x, player.y, player.w, player.h); ctx.fillStyle = '#c33'; ctx.fillRect(player.x + 2, player.y + 2, 6, 3);
  cam.end(ctx);
  text(ctx, 'Coins ' + score + '/' + totalCoins, 10, 10, { size: 18, bold: true });
  dialogue.draw(ctx, canvas);
  input.draw(ctx);
}

window.render_game_to_text = () => JSON.stringify({ player: { tx: Math.floor((player.x + 5) / ts), ty: Math.floor((player.y + 5) / ts) }, coins: score, totalCoins, dialogue: dialogue.active, walking: path.length, told });
new Loop(update, render).start();
