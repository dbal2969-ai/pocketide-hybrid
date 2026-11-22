// Side-scrolling platformer: run, jump, stomp the walkers, collect coins, reach the flag. Edit ROWS to build your own level.
//   # ground   = platform   c coin   e walker   g goal flag   p start
import { Loop } from './core/loop.js';
import { Input } from './core/input.js';
import { TileMap } from './core/tilemap.js';
import { Camera } from './core/camera.js';
import { moveBox, overlap } from './core/grid.js';
import { Entities } from './core/entities.js';
import { StateMachine } from './core/fsm.js';
import { Tweens } from './core/tween.js';
import { Button, text } from './core/ui.js';
import { Save } from './core/save.js';
import { Sound } from './core/sound.js';

const ROWS = [
  '                                                  ',
  '                                                  ',
  '                                                  ',
  '                                                g ',
  '                      ccc                       # ',
  '              c     =====          c   ===       #',
  '                                  ===           # ',
  '        ===       e         c                   # ',
  '   c          ===    ====     ===        e       # ',
  '                                     ======      #',
  ' p        e                  e                    ',
  '################   #########################  ####',
  '################   #########################  ####',
];
const map = TileMap.fromText(ROWS, { '#': { tile: 1, solid: true }, '=': { tile: 2, solid: true } }, 16, { c: 'coin', e: 'walker', g: 'goal', p: 'player' });
const ts = map.tileSize;

const canvas = document.getElementById('game'), ctx = canvas.getContext('2d');
const cam = new Camera(1, 1); cam.setBounds(map.width * ts, map.height * ts);
function fit() { canvas.width = innerWidth; canvas.height = innerHeight; cam.resize(canvas.width, canvas.height); cam.zoom = Math.max(2, Math.min(5, canvas.height / (13 * ts))); }
addEventListener('resize', fit); fit();

const input = new Input(canvas);
input.bind('jump', ['Space', 'ArrowUp', 'KeyW', 'KeyZ']);
input.addButton('jump', 'A', (w, h) => [w - 60, h - 70], 36);
const sound = new Sound(); sound.load('coin', 'assets/coin.wav'); sound.load('jump', 'assets/jump.wav'); sound.load('hurt', 'assets/hurt.wav'); sound.load('win', 'assets/win.wav');
const save = new Save('platformer');
const tweens = new Tweens();
const restart = new Button('Play again', 0, 0, 160, 44, () => fsm.go('play'));

let world, player, coins, lives, best = save.read('best', 0), flash = 0;
function reset() {
  world = new Entities(); coins = 0; lives = 3;
  const s = map.objectsOf('player')[0] || { x: 1, y: 1 };
  player = world.add({ kind: 'player', x: s.x * ts + 2, y: s.y * ts + 2, w: 12, h: 14, vx: 0, vy: 0, ground: false, coyote: 0, face: 1, spawn: { x: s.x * ts + 2, y: s.y * ts + 2 } });
  map.objectsOf('coin').forEach(o => world.add({ kind: 'coin', x: o.x * ts + 4, y: o.y * ts + 4, w: 8, h: 8 }));
  map.objectsOf('walker').forEach(o => world.add({ kind: 'walker', x: o.x * ts + 2, y: o.y * ts + 2, w: 12, h: 12, vx: 30, vy: 0 }));
  const g = map.objectsOf('goal')[0]; if (g) world.add({ kind: 'goal', x: g.x * ts, y: g.y * ts - ts, w: ts, h: ts * 2 });
}

const blocked = (x, y) => map.solid(x, y);
function hurt() {
  sound.play('hurt'); cam.shake(5, 0.3); flash = 0.4;
  if (--lives <= 0) { fsm.go('over'); return; }
  player.x = player.spawn.x; player.y = player.spawn.y; player.vx = player.vy = 0;
}

const fsm = new StateMachine({
  play: {
    enter() { reset(); },
    update(dt) {
      const a = input.axis();
      player.vx = a.x * 120; if (a.x) player.face = Math.sign(a.x);
      player.vy = Math.min(player.vy + 900 * dt, 420);
      player.coyote = player.ground ? 0.1 : player.coyote - dt;
      if (input.pressed('jump') && player.coyote > 0) { player.vy = -300; player.coyote = 0; sound.play('jump'); }
      if (!input.down('jump') && player.vy < -120) player.vy = -120; // let go early = shorter jump
      moveBox(player, player.vx * dt, 0, blocked, ts);
      const r = moveBox(player, 0, player.vy * dt, blocked, ts);
      player.ground = r.ground; if (r.hitY) player.vy = 0;
      if (player.y > map.height * ts + 40) hurt();
      for (const e of world.kind('walker')) {
        const ahead = e.x + (e.vx > 0 ? e.w + 1 : -1), foot = e.y + e.h + 1;
        if (map.solid(Math.floor(ahead / ts), Math.floor((e.y + e.h / 2) / ts)) || !map.solid(Math.floor(ahead / ts), Math.floor(foot / ts))) e.vx = -e.vx;
        e.vy = Math.min(e.vy + 900 * dt, 300);
        moveBox(e, e.vx * dt, 0, blocked, ts); moveBox(e, 0, e.vy * dt, blocked, ts);
        if (overlap(player, e)) {
          if (player.vy > 40 && player.y + player.h - e.y < 10) { world.remove(e); player.vy = -200; sound.play('coin'); coins += 2; } else hurt();
        }
      }
      for (const c of world.kind('coin')) if (overlap(player, c)) { world.remove(c); coins++; sound.play('coin'); }
      const g = world.kind('goal')[0]; if (g && overlap(player, g)) { if (coins > best) { best = coins; save.write('best', best); } sound.play('win'); fsm.go('won'); }
      world.sweep(); tweens.update(dt); flash = Math.max(0, flash - dt);
      cam.follow(player.x + player.w / 2, player.y, dt, 6);
    },
  },
  won: { enter() { this.t = 0; }, update(dt) { this.t += dt; } },
  over: { update() {} },
}, 'play');

function drawWorld() {
  cam.begin(ctx); map.draw(ctx, cam);
  for (const c of world.kind('coin')) { ctx.fillStyle = '#fd3'; ctx.beginPath(); ctx.arc(c.x + 4, c.y + 4, 4, 0, 7); ctx.fill(); }
  for (const e of world.kind('walker')) { ctx.fillStyle = '#d44'; ctx.fillRect(e.x, e.y, e.w, e.h); ctx.fillStyle = '#fff'; ctx.fillRect(e.x + 3, e.y + 3, 2, 2); ctx.fillRect(e.x + 8, e.y + 3, 2, 2); }
  const g = world.kind('goal')[0]; if (g) { ctx.fillStyle = '#8f8'; ctx.fillRect(g.x + 6, g.y, 2, g.h); ctx.fillStyle = '#fff'; ctx.fillRect(g.x + 8, g.y, 8, 6); }
  ctx.fillStyle = flash > 0 && Math.floor(flash * 20) % 2 ? '#f88' : '#4af'; ctx.fillRect(player.x, player.y, player.w, player.h);
  ctx.fillStyle = '#fff'; ctx.fillRect(player.x + (player.face > 0 ? 7 : 2), player.y + 3, 3, 3);
  cam.end(ctx);
}

function render() {
  ctx.fillStyle = '#5ab0e8'; ctx.fillRect(0, 0, canvas.width, canvas.height);
  drawWorld();
  text(ctx, 'Coins ' + coins + '   Lives ' + lives + '   Best ' + best, 10, 10, { size: 18, bold: true });
  if (fsm.name !== 'play') {
    ctx.fillStyle = 'rgba(0,0,0,0.6)'; ctx.fillRect(0, 0, canvas.width, canvas.height);
    text(ctx, fsm.name === 'won' ? 'You made it!  Coins: ' + coins : 'Game over', canvas.width / 2, canvas.height / 2 - 50, { size: 28, align: 'center', bold: true });
    restart.x = canvas.width / 2 - 80; restart.y = canvas.height / 2; restart.draw(ctx);
    for (const t of input.takeTaps()) restart.tap(t.x, t.y);
  } else input.takeTaps();
  input.draw(ctx);
}

function update(dt) { fsm.update(dt); input.endFrame(); }
window.render_game_to_text = () => JSON.stringify({ state: fsm.name, player: { x: Math.round(player.x), y: Math.round(player.y), onGround: player.ground }, coins, lives, walkers: world.count('walker') });
new Loop(update, render).start();
