// Dodge: blocks fall; move with the arrow keys. window.game holds the state.
const canvas = document.getElementById('field');
const ctx = canvas.getContext('2d');
const game = {
  x: 180, blocks: [], ticks: 0, over: false, loop: null,
  restart() {
    this.x = 180; this.blocks = []; this.ticks = 0; this.over = false;
    document.getElementById('status').textContent = 'Playing';
    this.loop = setInterval(() => this.tick(), 100);
  },
  tick() {
    if (this.over) return;
    this.ticks++;
    if (this.ticks % 5 === 0) this.blocks.push({ x: (this.ticks * 37) % 360, y: 0 });
    for (const b of this.blocks) b.y += 20;
    this.blocks = this.blocks.filter(b => b.y < 400);
    if (this.blocks.some(b => b.y >= 360 && Math.abs(b.x - this.x) < 40)) {
      this.over = true; document.getElementById('status').textContent = 'Game over';
    }
    draw();
  },
};
window.game = game;

function draw() {
  ctx.fillStyle = '#000'; ctx.fillRect(0, 0, 400, 400);
  ctx.fillStyle = '#4af'; ctx.fillRect(game.x, 370, 40, 20);
  ctx.fillStyle = '#f44'; for (const b of game.blocks) ctx.fillRect(b.x, b.y, 40, 20);
  document.getElementById('time').textContent = 'Time: ' + game.ticks;
}

document.addEventListener('keydown', e => {
  if (e.key === 'ArrowLeft') game.x = Math.max(0, game.x - 20);
  if (e.key === 'ArrowRight') game.x = Math.min(360, game.x + 20);
});
document.getElementById('restart').onclick = () => game.restart();
game.restart();
