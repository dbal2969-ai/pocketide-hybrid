// Tile grid collision for any tile-based game. Works with pathfinder.js (same walkable/canStep calls as world.js)
// and moves boxes against solid tiles for platformers and top-down games.
// Part of PocketIDE's game kit; MIT licensed.

export class Grid {
  // solid(x, y) says whether tile (x, y) blocks; outside the map always blocks.
  constructor(width, height, solid) { this.width = width; this.height = height; this.solid = solid; }

  inside(x, y) { return x >= 0 && y >= 0 && x < this.width && y < this.height; }
  blocked(x, y) { return !this.inside(x, y) || !!this.solid(x, y); }

  // pathfinder interface: tile columns are x, rows are z
  walkable(x, z) { return !this.blocked(x, z); }
  canStep(x, z, dx, dz) {
    if (dx === 0 && dz === 0) return false;
    if (this.blocked(x + dx, z + dz)) return false;
    if (dx !== 0 && dz !== 0 && (this.blocked(x + dx, z) || this.blocked(x, z + dz))) return false; // no cutting corners
    return true;
  }
}

// Move a box {x, y, w, h} (pixels) by (dx, dy) against solid tiles of size ts. Axes are resolved one at a time so boxes slide along walls.
// Returns {hitX, hitY, ground} where ground is true when the box was stopped while moving down.
export function moveBox(box, dx, dy, blocked, ts) {
  const res = { hitX: false, hitY: false, ground: false };
  const overlaps = () => {
    const x0 = Math.floor(box.x / ts), x1 = Math.floor((box.x + box.w - 0.001) / ts), y0 = Math.floor(box.y / ts), y1 = Math.floor((box.y + box.h - 0.001) / ts);
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) if (blocked(x, y)) return [x, y];
    return null;
  };
  box.x += dx;
  let hit = overlaps();
  if (hit) { box.x = dx > 0 ? hit[0] * ts - box.w : (hit[0] + 1) * ts; res.hitX = true; }
  box.y += dy;
  hit = overlaps();
  if (hit) { if (dy > 0) { box.y = hit[1] * ts - box.h; res.ground = true; } else box.y = (hit[1] + 1) * ts; res.hitY = true; }
  return res;
}

export function overlap(a, b) { return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y; }
