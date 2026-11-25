// Paddle: keep the ball up with the paddle. window.game holds the state.
const canvas = document.getElementById('court');
const ctx = canvas.getContext('2d');
const game = {
  paddle: { x: 160, w: 80, y: 380 },
  ball: { x: 200, y: 100, dx: 3, dy: 3 },
  speed: 20, misses: 0,
  move(dx) {
    this.paddle.x = Math.max(0, Math.min(canvas.width - this.paddle.w, this.paddle.x + dx));
    draw();
  },
  tick() {
    const b = this.ball;
    b.x += b.dx; b.y += b.dy;
    if (b.x < 0 || b.x > canvas.width) b.dx = -b.dx;
    if (b.y < 0) b.dy = -b.dy;
    if (b.y >= this.paddle.y && b.x >= this.paddle.x && b.x <= this.paddle.x + this.paddle.w) b.dy = -Math.abs(b.dy);
    if (b.y > canvas.height) { this.misses++; b.x = 200; b.y = 100; }
    draw();
  },
};
window.game = game;

function draw() {
  ctx.fillStyle = '#000'; ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = '#fff'; ctx.fillRect(game.paddle.x, game.paddle.y, game.paddle.w, 10);
  ctx.beginPath(); ctx.arc(game.ball.x, game.ball.y, 6, 0, Math.PI * 2); ctx.fill();
  document.getElementById('misses').textContent = 'Misses: ' + game.misses;
}

document.addEventListener('keydown', e => {
  if (e.key === 'ArrowLeft') { game.move(-game.speed); e.preventDefault(); }
  if (e.key === 'ArrowRight') { game.move(game.speed); e.preventDefault(); }
});
setInterval(() => game.tick(), 16);
draw();
