// Shortest walking path on a World (breadth-first, 8 directions, every step costs one tick like the original game).
// Written for PocketIDE's game kit; MIT licensed. Idea of the search follows common RuneScape route finders (rsmod, ISC).
import { FULL } from './world.js';

const DIRS = [[0, 1], [1, 0], [0, -1], [-1, 0], [1, 1], [1, -1], [-1, -1], [-1, 1]];

// Returns an array of {x, z} steps from the tile after the start to the end, [] when already there,
// or null when the end cannot be reached. If `near` is true and the end cannot be reached, walks to the closest tile
// that can (like clicking a wall). `radius` limits the search square around the start (default 64).
export function findPath(world, sx, sz, ex, ez, { plane = 0, near = false, radius = 64 } = {}) {
  if (sx === ex && sz === ez) return [];
  const size = radius * 2 + 1;
  const ox = sx - radius, oz = sz - radius;
  const from = new Int32Array(size * size).fill(-1);
  const idx = (x, z) => (x - ox) * size + (z - oz);
  const queue = new Int32Array(size * size);
  let head = 0, tail = 0;
  const start = idx(sx, sz);
  from[start] = start; queue[tail++] = start;
  let best = start, bestDist = Math.max(Math.abs(sx - ex), Math.abs(sz - ez)), found = -1;
  while (head < tail) {
    const cur = queue[head++];
    const cx = Math.floor(cur / size) + ox, cz = (cur % size) + oz;
    if (cx === ex && cz === ez) { found = cur; break; }
    for (const [dx, dz] of DIRS) {
      const nx = cx + dx, nz = cz + dz;
      if (Math.abs(nx - sx) > radius || Math.abs(nz - sz) > radius) continue;
      const ni = idx(nx, nz);
      if (from[ni] !== -1) continue;
      if (!world.canStep(cx, cz, dx, dz, plane)) continue;
      from[ni] = cur; queue[tail++] = ni;
      const d = Math.max(Math.abs(nx - ex), Math.abs(nz - ez));
      if (d < bestDist) { bestDist = d; best = ni; }
    }
  }
  let target = found;
  if (target === -1) { if (!near || best === start) return null; target = best; }
  const path = [];
  for (let c = target; c !== start; c = from[c]) path.push({ x: Math.floor(c / size) + ox, z: (c % size) + oz });
  return path.reverse();
}

// Closest walkable tile to (x, z) within `max` tiles, or null.
export function nearestWalkable(world, x, z, plane = 0, max = 8) {
  for (let r = 0; r <= max; r++)
    for (let dx = -r; dx <= r; dx++)
      for (let dz = -r; dz <= r; dz++)
        if (Math.max(Math.abs(dx), Math.abs(dz)) === r && world.walkable(x + dx, z + dz, plane)) return { x: x + dx, z: z + dz };
  return null;
}
