// Snake: see GAME.md. window.game is the game's state and controls.
const SIZE = 20, CELL = 20;
const canvas = document.getElementById('board');
const ctx = canvas.getContext('2d');
const DIRS = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] };
const OPPOSITE = { up: 'down', down: 'up', left: 'right', right: 'left' };
const KEYS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };

const game = {
  snake: [], dir: 'right', next: 'right', food: { x: 0, y: 0 }, score: 0, over: false, timer: null,
  reset() {
    this.snake = [{ x: 5, y: 10 }, { x: 4, y: 10 }, { x: 3, y: 10 }];
    this.dir = this.next = 'right';
    this.score = 0; this.over = false;
    this.placeFood();
    this.stop();
    show('Press Start or Space');
    draw();
  },
  placeFood() {
    do { this.food = { x: Math.floor(Math.random() * SIZE), y: Math.floor(Math.random() * SIZE) }; }
    while (this.snake.some(p => p.x === this.food.x && p.y === this.food.y));
  },
  turn(d) { if (d && d !== OPPOSITE[this.dir]) this.next = d; },
  step() {
    if (this.over) return;
    this.dir = this.next;
    const [dx, dy] = DIRS[this.dir];
    const head = { x: this.snake[0].x + dx, y: this.snake[0].y + dy };
    if (head.x < 0 || head.y < 0 || head.x >= SIZE || head.y >= SIZE || this.snake.some(p => p.x === head.x && p.y === head.y)) {
      this.over = true; this.stop(); show('Game over'); draw(); return;
    }
    this.snake.unshift(head);
    if (head.x === this.food.x && head.y === this.food.y) { this.score++; this.placeFood(); }
    else this.snake.pop();
    draw();
  },
  start() { if (this.over) this.reset(); this.stop(); this.timer = setInterval(() => this.step(), 150); show('Playing'); },
  stop() { if (this.timer) clearInterval(this.timer); this.timer = null; },
};
window.game = game;

function show(s) { document.getElementById('status').textContent = s; }
function draw() {
  ctx.fillStyle = '#000'; ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = '#e33'; ctx.fillRect(game.food.x * CELL, game.food.y * CELL, CELL, CELL);
  ctx.fillStyle = '#3c3'; for (const p of game.snake) ctx.fillRect(p.x * CELL, p.y * CELL, CELL - 1, CELL - 1);
  document.getElementById('score').textContent = 'Score: ' + game.score;
}

document.addEventListener('keydown', e => {
  if (KEYS[e.key]) { game.turn(KEYS[e.key]); e.preventDefault(); }
  else if (e.key === ' ') { game.start(); e.preventDefault(); }
});
document.getElementById('start').onclick = () => game.start();
game.reset();
