// Empty game: a loop, touch/keyboard input and a canvas that fits the screen. Build on it.
import { Loop } from './core/loop.js';
import { Input } from './core/input.js';
import { text } from './core/ui.js';

const canvas = document.getElementById('game'), ctx = canvas.getContext('2d');
function fit() { canvas.width = innerWidth; canvas.height = innerHeight; }
addEventListener('resize', fit); fit();

const input = new Input(canvas);
input.bind('action', ['Space', 'Enter']);
input.addButton('action', 'A', (w, h) => [w - 60, h - 70], 34);
const me = { x: canvas.width / 2, y: canvas.height / 2, hits: 0 };

function update(dt) {
  const a = input.axis(); me.x += a.x * 200 * dt; me.y += a.y * 200 * dt;
  if (input.pressed('action')) me.hits++;
  for (const t of input.takeTaps()) { me.x = t.x; me.y = t.y; }
  input.endFrame();
}
function render() {
  ctx.fillStyle = '#16202c'; ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = '#fc4'; ctx.beginPath(); ctx.arc(me.x, me.y, 16, 0, 7); ctx.fill();
  text(ctx, 'Move with the joystick or arrows, tap to jump there, A to count: ' + me.hits, 10, 10, { size: 14 });
  input.draw(ctx);
}
window.render_game_to_text = () => JSON.stringify({ x: Math.round(me.x), y: Math.round(me.y), hits: me.hits });
new Loop(update, render).start();
