// Tic-tac-toe: see GAME.md.
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
