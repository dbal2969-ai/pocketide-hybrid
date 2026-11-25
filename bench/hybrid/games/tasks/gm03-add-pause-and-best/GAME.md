# Snake

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
