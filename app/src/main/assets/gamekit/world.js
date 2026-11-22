// World: tile collision loaded from map squares exported by game_cache (maps/m<x>_<z>.json).
// Wall bits per tile: N=1 E=2 S=4 W=8 (a wall on that edge), FULL=16 (tile cannot be entered).
// Written for PocketIDE's game kit; MIT licensed.
export const N = 1, E = 2, S = 4, W = 8, FULL = 16;
const CHARS = '0123456789abcdefghijklmnopqrstuv';
const CODE = {}; for (let i = 0; i < CHARS.length; i++) CODE[CHARS[i]] = i;

export class World {
  constructor() { this.squares = new Map(); }

  // json: one exported map square. Squares without a collision grid fall back to the terrain grid (# blocked).
  addSquare(json) {
    const key = json.x + ',' + json.z;
    const planes = [];
    for (let p = 0; p < 4; p++) {
      const f = new Uint8Array(4096);
      const rows = json.collision ? json.collision[p] : null;
      const terr = json.planes ? json.planes[p] : null;
      for (let row = 0; row < 64; row++) {
        const z = 63 - row;
        for (let x = 0; x < 64; x++) {
          if (rows) f[x * 64 + z] = CODE[rows[row][x]] || 0;
          else if (terr && terr[row][x] === '#') f[x * 64 + z] = FULL;
        }
      }
      planes.push(f);
    }
    this.squares.set(key, { json, planes });
  }

  async load(urls) {
    for (const u of urls) {
      try { const r = await fetch(u); if (r.ok) this.addSquare(await r.json()); } catch (e) { /* missing square stays void */ }
    }
    return this;
  }

  // Flags of a world tile; unknown space is FULL (the void cannot be walked).
  flags(x, z, plane = 0) {
    const sq = this.squares.get((x >> 6) + ',' + (z >> 6));
    if (!sq) return FULL;
    return sq.planes[plane][(x & 63) * 64 + (z & 63)];
  }

  walkable(x, z, plane = 0) { return (this.flags(x, z, plane) & FULL) === 0; }

  // Can one step of (dx, dz), each -1, 0 or 1, be made from (x, z)? Diagonals need both straight steps to be possible.
  canStep(x, z, dx, dz, plane = 0) {
    if (dx === 0 && dz === 0) return false;
    if (dx !== 0 && dz !== 0) {
      return this.canStep(x, z, dx, 0, plane) && this.canStep(x, z, 0, dz, plane) &&
             this.canStep(x + dx, z, 0, dz, plane) && this.canStep(x, z + dz, dx, 0, plane);
    }
    const here = this.flags(x, z, plane), there = this.flags(x + dx, z + dz, plane);
    if (there & FULL) return false;
    if (dz === 1 && ((here & N) || (there & S))) return false;
    if (dz === -1 && ((here & S) || (there & N))) return false;
    if (dx === 1 && ((here & E) || (there & W))) return false;
    if (dx === -1 && ((here & W) || (there & E))) return false;
    return true;
  }

  // Object list of a square: [id, x, z, plane, type, rotation] with local x, z. Returns world coordinates.
  objects(rx, rz) {
    const sq = this.squares.get(rx + ',' + rz);
    if (!sq || !sq.json.objects) return [];
    return sq.json.objects.map(o => ({ id: o[0], x: rx * 64 + o[1], z: rz * 64 + o[2], plane: o[3], type: o[4], rot: o[5] }));
  }
}

// Wall bits a wall-type object (type 0 straight, type 2 corner) puts on its tile, by rotation.
export function wallBits(type, rot) {
  if (type === 0) return [W, N, E, S][rot & 3];
  if (type === 2) return [W | N, N | E, E | S, S | W][rot & 3];
  return 0;
}

// Open or close a wall object (a door): `blocking` false removes its wall bits, true puts them back. o is an item of world.objects().
World.prototype.setWallBlocking = function (o, blocking) {
  const bits = wallBits(o.type, o.rot);
  if (!bits) return false;
  const sq = this.squares.get((o.x >> 6) + ',' + (o.z >> 6));
  if (!sq) return false;
  const f = sq.planes[o.plane];
  const set = (x, z, b) => {
    const s2 = this.squares.get((x >> 6) + ',' + (z >> 6)); if (!s2) return;
    const i = (x & 63) * 64 + (z & 63);
    s2.planes[o.plane][i] = blocking ? (s2.planes[o.plane][i] | b) : (s2.planes[o.plane][i] & ~b);
  };
  set(o.x, o.z, bits);
  if (bits & N) set(o.x, o.z + 1, S);
  if (bits & S) set(o.x, o.z - 1, N);
  if (bits & E) set(o.x + 1, o.z, W);
  if (bits & W) set(o.x - 1, o.z, E);
  return true;
};
