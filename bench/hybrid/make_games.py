"""The game benchmark: five browser-game tasks checked by the app's play tests (tests/play.txt), the way PocketIDE checks games.

Each task targets a way AI-built games often break: keyboard input that never reaches the game (the owner's snake moved but ignored
the keys), a game built from a spec with exact rules, a feature added without breaking play, a turn-based game with an opponent,
and a restart that stacks game loops. Every play test fails as given and passes with the reference fix (check_games.py).

Writes bench/hybrid/games/tasks/<id>/, zips/<id>.zip and solutions/<id>/.
Usage: python3 bench/hybrid/make_games.py bench/hybrid/games
"""
import os, shutil, sys, zipfile

OUT = sys.argv[1]
T, S = {}, {}

PAGE = '''<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>{title}</title>
<link rel="stylesheet" href="style.css"></head>
<body>
{body}
<script src="game.js"></script>
</body>
</html>
'''
CSS = "body { font-family: sans-serif; margin: 12px; background: #111; color: #eee; }\ncanvas { background: #000; display: block; margin: 8px 0; }\nbutton { font-size: 16px; padding: 6px 12px; margin-right: 6px; }\n"

# ------------------------------------------------------------------------------------------------- the reference snake (gm01, gm03)
SNAKE_BODY = '''  <h1>Snake</h1>
  <p><span id="score">Score: 0</span> <span id="best"></span></p>
  <p id="status">Press Start or Space</p>
  <button id="start">Start</button>
  <canvas id="board" width="400" height="400"></canvas>'''
SNAKE_JS = r'''// Snake: see GAME.md. window.game is the game's state and controls.
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
'''
GAME_MD = r'''# Snake

Build the game in `index.html`, `style.css` and `game.js`. Tests drive it through `window.game`, so these must exist exactly:

- A 20 × 20 grid drawn on a `<canvas>`. Cells are numbered from 0; x grows to the right, y grows downwards.
- `game.snake`: an array of `{x, y}`, head first. After `game.reset()` it is `[{x:5,y:10},{x:4,y:10},{x:3,y:10}]`, moving right.
- `game.step()` moves the snake one cell. It does nothing once `game.over` is true.
- The arrow keys turn the snake (on the next step). Turning straight back (left while moving right) is ignored.
  The keys must work as soon as the page loads, without clicking the canvas first.
- `game.food`: `{x, y}`. When the head reaches the food the snake grows by one, `game.score` goes up by one and new food appears
  somewhere not on the snake.
- Hitting a wall or itself sets `game.over` to true.
- `#score` shows `Score: N`. `#status` shows `Game over` when the game ends.
- The game starts paused. A **Start** button (and Space) starts it: it then steps on its own about every 150 ms.
'''
SNAKE_PLAY = r'''# moves right by itself on each step
run game.reset()
run game.step()
expect game.snake[0].x === 6 && game.snake[0].y === 10
expect game.snake.length === 3
# arrow keys turn the snake without clicking first
run game.reset()
key ArrowUp
run game.step()
expect game.snake[0].x === 5 && game.snake[0].y === 9
key ArrowLeft
run game.step()
expect game.snake[0].x === 4 && game.snake[0].y === 9
# turning straight back is ignored
run game.reset()
key ArrowLeft
run game.step()
expect game.snake[0].x === 6 && game.snake[0].y === 10
# eating food grows the snake and scores
run game.reset(); game.food = { x: 6, y: 10 }
run game.step()
expect game.snake.length === 4 && game.score === 1
expect !(game.food.x === 6 && game.food.y === 10)
expect document.getElementById('score').textContent.includes('1')
# hitting the wall ends the game
run game.reset(); for (let i = 0; i < 20; i++) game.step()
expect game.over === true
expect document.getElementById('status').textContent.toLowerCase().includes('game over')
run window.__len = game.snake.length; window.__head = JSON.stringify(game.snake[0]); game.step()
expect JSON.stringify(game.snake[0]) === window.__head
# Start runs the game on its own
run game.reset()
tap "Start"
until 3000 game.snake[0].x >= 8
'''
T["gm01-snake-from-spec"] = {
 "GAME.md": GAME_MD,
 "index.html": PAGE.format(title="Snake", body="  <h1>Snake</h1>\n  <!-- build the game here: see GAME.md -->"),
 "style.css": CSS,
 "game.js": "// Snake: see GAME.md.\n",
 "tests/play.txt": SNAKE_PLAY,
}
S["gm01-snake-from-spec"] = {"index.html": PAGE.format(title="Snake", body=SNAKE_BODY), "game.js": SNAKE_JS}

# ------------------------------------------------------------------------------------------------- gm02: keys never reach the game
PADDLE_JS = r'''// Paddle: keep the ball up with the paddle. window.game holds the state.
const canvas = document.getElementById('court');
const ctx = canvas.getContext('2d');
const game = {
  paddle: { x: 160, w: 80, y: 380 },
  ball: { x: 200, y: 100, dx: 3, dy: 3 },
  speed: 20, misses: 0,
  move(dx) {
    this.paddle.x = this.paddle.x + dx;
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

canvas.addEventListener('keydown', e => {
  if (e.keyCode === 37) game.move(-game.speed);
  if (e.keyCode === 39) game.move(game.speed);
});
setInterval(() => game.tick(), 16);
draw();
'''
PADDLE_PLAY = r'''# the arrow keys move the paddle, without clicking the game first
run window.__x = game.paddle.x
key ArrowLeft
expect game.paddle.x < window.__x
run window.__x = game.paddle.x
key ArrowRight
key ArrowRight
expect game.paddle.x > window.__x
# the paddle stays inside the court
run game.paddle.x = 30
key ArrowLeft
key ArrowLeft
expect game.paddle.x === 0
run game.paddle.x = 300
key ArrowRight
key ArrowRight
expect game.paddle.x === 400 - game.paddle.w
# the ball still moves
run window.__b = game.ball.x + ',' + game.ball.y
wait 300
expect game.ball.x + ',' + game.ball.y !== window.__b
'''
T["gm02-fix-keyboard"] = {
 "index.html": PAGE.format(title="Paddle", body='  <h1>Paddle</h1>\n  <p id="misses">Misses: 0</p>\n  <canvas id="court" width="400" height="400"></canvas>'),
 "style.css": CSS, "game.js": PADDLE_JS, "tests/play.txt": PADDLE_PLAY,
}
S["gm02-fix-keyboard"] = {"game.js": PADDLE_JS
  .replace("    this.paddle.x = this.paddle.x + dx;", "    this.paddle.x = Math.max(0, Math.min(canvas.width - this.paddle.w, this.paddle.x + dx));")
  .replace("canvas.addEventListener('keydown', e => {\n  if (e.keyCode === 37) game.move(-game.speed);\n  if (e.keyCode === 39) game.move(game.speed);\n});",
           "document.addEventListener('keydown', e => {\n  if (e.key === 'ArrowLeft') { game.move(-game.speed); e.preventDefault(); }\n  if (e.key === 'ArrowRight') { game.move(game.speed); e.preventDefault(); }\n});")}

# ------------------------------------------------------------------------------------------------- gm03: add features to a working game
FEATURE_PLAY = SNAKE_PLAY + r'''# P pauses and resumes a running game
run game.reset()
tap "Start"
key p
expect game.paused === true
expect document.getElementById('status').textContent.toLowerCase().includes('paused')
run window.__head = JSON.stringify(game.snake[0])
wait 500
expect JSON.stringify(game.snake[0]) === window.__head
key p
expect game.paused === false
until 2000 JSON.stringify(game.snake[0]) !== window.__head
# the best score is kept when the game ends
run localStorage.removeItem('snakeBest'); game.reset(); game.food = { x: 6, y: 10 }; game.step(); game.food = { x: 7, y: 10 }; game.step()
expect game.score === 2
run for (let i = 0; i < 20; i++) game.step()
expect game.over === true
expect localStorage.getItem('snakeBest') === '2'
expect document.getElementById('best').textContent.includes('Best: 2')
run game.reset(); game.food = { x: 6, y: 10 }; game.step(); for (let i = 0; i < 20; i++) game.step()
expect localStorage.getItem('snakeBest') === '2'
'''
T["gm03-add-pause-and-best"] = {
 "GAME.md": GAME_MD, "index.html": PAGE.format(title="Snake", body=SNAKE_BODY), "style.css": CSS, "game.js": SNAKE_JS,
 "tests/play.txt": FEATURE_PLAY,
}
S["gm03-add-pause-and-best"] = {"game.js": SNAKE_JS
  .replace("  snake: [], dir: 'right', next: 'right', food: { x: 0, y: 0 }, score: 0, over: false, timer: null,",
           "  snake: [], dir: 'right', next: 'right', food: { x: 0, y: 0 }, score: 0, over: false, timer: null, paused: false,\n  best: Number(localStorage.getItem('snakeBest') || 0),")
  .replace("    this.score = 0; this.over = false;", "    this.score = 0; this.over = false; this.paused = false;")
  .replace("      this.over = true; this.stop(); show('Game over'); draw(); return;",
           "      this.over = true; this.stop(); show('Game over');\n      if (this.score > this.best) { this.best = this.score; localStorage.setItem('snakeBest', String(this.best)); }\n      draw(); return;")
  .replace("  stop() {", "  pause() {\n    if (!this.timer && !this.paused) return;\n    this.paused = !this.paused;\n    if (this.paused) { clearInterval(this.timer); this.timer = null; show('Paused'); }\n    else { this.timer = setInterval(() => this.step(), 150); show('Playing'); }\n  },\n  stop() {")
  .replace("  document.getElementById('score').textContent = 'Score: ' + game.score;",
           "  document.getElementById('score').textContent = 'Score: ' + game.score;\n  document.getElementById('best').textContent = game.best ? 'Best: ' + game.best : '';")
  .replace("  else if (e.key === ' ') { game.start(); e.preventDefault(); }",
           "  else if (e.key === ' ') { game.start(); e.preventDefault(); }\n  else if (e.key === 'p' || e.key === 'P') game.pause();")}

# ------------------------------------------------------------------------------------------------- gm04: tic-tac-toe with an opponent
TTT_MD = r'''# Tic-tac-toe

Build it in `index.html`, `style.css` and `game.js`.

- Nine buttons with the class `cell` and `data-i="0"` … `data-i="8"`, left to right, top to bottom.
- The player is X and moves by tapping an empty cell. The computer (O) answers at once, choosing, in this order:
  1. a cell that wins for O; 2. a cell that blocks X from winning next move; 3. the centre (4); 4. the lowest free corner
  (0, 2, 6, 8); 5. the lowest free cell.
- A cell shows `X` or `O`. Tapping a used cell, or any cell after the game ends, does nothing.
- `#status` says `X wins`, `O wins` or `Draw` when the game ends.
- A **New game** button clears the board.
'''
TTT_PLAY = r'''# the computer takes the centre after X opens in a corner
tap [data-i="0"]
expect document.querySelector('[data-i="0"]').textContent === 'X'
expect document.querySelector('[data-i="4"]').textContent === 'O'
# the computer blocks a row
tap [data-i="0"]
tap [data-i="1"]
expect document.querySelector('[data-i="2"]').textContent === 'O'
# the computer takes a win when it has one
tap [data-i="0"]
tap [data-i="1"]
tap [data-i="3"]
expect document.querySelector('[data-i="6"]').textContent === 'O'
expect document.getElementById('status').textContent === 'O wins'
tap [data-i="8"]
expect document.querySelector('[data-i="8"]').textContent === ''
# a used cell cannot be taken again, and New game clears the board
tap [data-i="0"]
tap [data-i="4"]
expect document.querySelector('[data-i="4"]').textContent === 'O'
expect document.querySelectorAll('.cell').length === 9
tap "New game"
expect [...document.querySelectorAll('.cell')].every(c => c.textContent === '')
# a full board with no line is a draw
tap [data-i="4"]
tap [data-i="2"]
tap [data-i="3"]
tap [data-i="7"]
tap [data-i="8"]
expect document.getElementById('status').textContent === 'Draw'
'''
TTT_JS = r'''// Tic-tac-toe: see GAME.md.
const LINES = [[0,1,2],[3,4,5],[6,7,8],[0,3,6],[1,4,7],[2,5,8],[0,4,8],[2,4,6]];
let board, done;
const cells = [...document.querySelectorAll('.cell')];
const status = document.getElementById('status');

function winner(b) { for (const [a, c, d] of LINES) if (b[a] && b[a] === b[c] && b[a] === b[d]) return b[a]; return null; }
function free(b) { return b.map((v, i) => v ? -1 : i).filter(i => i >= 0); }
function finding(b, who) { for (const i of free(b)) { const t = b.slice(); t[i] = who; if (winner(t) === who) return i; } return -1; }
function choose(b) {
  let i = finding(b, 'O'); if (i >= 0) return i;
  i = finding(b, 'X'); if (i >= 0) return i;
  if (!b[4]) return 4;
  for (const c of [0, 2, 6, 8]) if (!b[c]) return c;
  return free(b)[0];
}
function end() {
  const w = winner(board);
  if (w) { status.textContent = w + ' wins'; done = true; }
  else if (!free(board).length) { status.textContent = 'Draw'; done = true; }
  return done;
}
function render() { cells.forEach((c, i) => c.textContent = board[i] || ''); }
function play(i) {
  if (done || board[i]) return;
  board[i] = 'X';
  if (!end()) { board[choose(board)] = 'O'; end(); }
  render();
}
function reset() { board = Array(9).fill(null); done = false; status.textContent = 'Your move'; render(); }
cells.forEach(c => c.onclick = () => play(Number(c.dataset.i)));
document.getElementById('new').onclick = reset;
reset();
'''
T["gm04-tictactoe-opponent"] = {
 "GAME.md": TTT_MD, "index.html": PAGE.format(title="Tic-tac-toe", body="  <h1>Tic-tac-toe</h1>\n  <!-- build the game here: see GAME.md -->"),
 "style.css": CSS + ".cell { width: 64px; height: 64px; font-size: 32px; margin: 2px; }\n", "game.js": "// Tic-tac-toe: see GAME.md.\n",
 "tests/play.txt": TTT_PLAY,
}
S["gm04-tictactoe-opponent"] = {
 "index.html": PAGE.format(title="Tic-tac-toe", body='  <h1>Tic-tac-toe</h1>\n  <p id="status"></p>\n  <div id="grid">\n' +
   "".join(f'    <button class="cell" data-i="{i}"></button>' + ("<br>\n" if i % 3 == 2 else "\n") for i in range(9)) +
   '  </div>\n  <button id="new">New game</button>'),
 "game.js": TTT_JS,
}

# ------------------------------------------------------------------------------------------------- gm05: restart stacks game loops
DODGE_JS = r'''// Dodge: blocks fall; move with the arrow keys. window.game holds the state.
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
'''
DODGE_PLAY = r'''# restarting does not make the game faster
tap "Restart"
tap "Restart"
tap "Restart"
wait 1000
expect game.ticks >= 5 && game.ticks <= 14
# the game still runs and restarts cleanly after a game over
run game.over = true
tap "Restart"
expect game.over === false && game.ticks <= 2
until 2000 game.ticks >= 5
# the keys move the player
run window.__x = game.x
key ArrowLeft
expect game.x === window.__x - 20
'''
T["gm05-restart-speedup"] = {
 "index.html": PAGE.format(title="Dodge", body='  <h1>Dodge</h1>\n  <p><span id="time">Time: 0</span> <span id="status"></span></p>\n  <button id="restart">Restart</button>\n  <canvas id="field" width="400" height="400"></canvas>'),
 "style.css": CSS, "game.js": DODGE_JS, "tests/play.txt": DODGE_PLAY,
}
S["gm05-restart-speedup"] = {"game.js": DODGE_JS.replace("    this.loop = setInterval(() => this.tick(), 100);",
                                                         "    if (this.loop) clearInterval(this.loop);\n    this.loop = setInterval(() => this.tick(), 100);")}

# ------------------------------------------------------------------------------------------------- write
if os.path.exists(OUT): shutil.rmtree(OUT)
for kind, src in (("tasks", T), ("solutions", S)):
    for name, files in src.items():
        for p, c in files.items():
            fp = os.path.join(OUT, kind, name, p); os.makedirs(os.path.dirname(fp), exist_ok=True)
            open(fp, "w").write(c)
os.makedirs(os.path.join(OUT, "zips"))
for name, files in T.items():
    with zipfile.ZipFile(os.path.join(OUT, "zips", name + ".zip"), "w", zipfile.ZIP_DEFLATED) as z:
        for p, c in sorted(files.items()):
            zi = zipfile.ZipInfo(p, date_time=(2026, 11, 25, 0, 0, 0)); zi.external_attr = 0o644 << 16
            z.writestr(zi, c)
print("written", len(T), "game tasks")
