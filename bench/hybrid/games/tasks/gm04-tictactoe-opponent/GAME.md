# Tic-tac-toe

Build it in `index.html`, `style.css` and `game.js`.

- Nine buttons with the class `cell` and `data-i="0"` … `data-i="8"`, left to right, top to bottom.
- The player is X and moves by tapping an empty cell. The computer (O) answers at once, choosing, in this order:
  1. a cell that wins for O; 2. a cell that blocks X from winning next move; 3. the centre (4); 4. the lowest free corner
  (0, 2, 6, 8); 5. the lowest free cell.
- A cell shows `X` or `O`. Tapping a used cell, or any cell after the game ends, does nothing.
- `#status` says `X wins`, `O wins` or `Draw` when the game ends.
- A **New game** button clears the board.
