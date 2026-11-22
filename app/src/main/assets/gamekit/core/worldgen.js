// An open world made in code from a short design: hills and mountains, water, roads, villages of houses with several floors (stairs
// inside, roofs and upper floors hide while you are inside), dungeons under the ground (rooms and corridors), trees and rocks.
// Everything is merged into a few meshes so it runs on a phone. Used by the rpg3d template; usable in any three.js game.
//   const world = buildWorld(THREE, scene, design);   // design: see templates/rpg3d/design.json
//   world.heightAt(x, z), world.collide(p, radius, where) (pushes p out of walls, trees and rocks), world.interactables (stairs, dungeon
//   doors, chests), world.villages, world.houses, world.dungeons, world.spots(n, area) (free places to put people or creatures).
// Coordinates: metres; x east, z south, y up. A dungeon is a separate area far from the surface (where.dungeon tells which).
// Part of PocketIDE's game kit; MIT licensed.
import { Noise, rng, seedOf } from './noise.js';

const FLOOR_H = 3, WALL_T = 0.3, CELL = 3;

// Many coloured boxes and roofs as one mesh (one draw call)
export class Batch {
  constructor(THREE) { this.THREE = THREE; this.pos = []; this.col = []; this.c = new THREE.Color(); }
  tri(a, b, c, color) { this.c.set(color); for (const p of [a, b, c]) { this.pos.push(p[0], p[1], p[2]); this.col.push(this.c.r, this.c.g, this.c.b); } }
  quad(a, b, c, d, color) { this.tri(a, b, c, color); this.tri(a, c, d, color); }
  // a box centred at x, z with its bottom at y, turned by rot radians about y; shade darkens the sides a little for depth
  box(x, y, z, w, h, d, color, rot = 0) {
    const cs = Math.cos(rot), sn = Math.sin(rot), P = (dx, dy, dz) => [x + dx * cs + dz * sn, y + dy, z - dx * sn + dz * cs];
    const hw = w / 2, hd = d / 2, v = [P(-hw, 0, -hd), P(hw, 0, -hd), P(hw, 0, hd), P(-hw, 0, hd), P(-hw, h, -hd), P(hw, h, -hd), P(hw, h, hd), P(-hw, h, hd)];
    const dark = shade(color, 0.85), darker = shade(color, 0.72);
    this.quad(v[4], v[7], v[6], v[5], color); this.quad(v[0], v[1], v[2], v[3], darker);
    this.quad(v[3], v[2], v[6], v[7], dark); this.quad(v[1], v[0], v[4], v[5], dark);
    this.quad(v[2], v[1], v[5], v[6], darker); this.quad(v[0], v[3], v[7], v[4], darker);
  }
  // a gable roof over a w by d rectangle at height y, ridge along the longer side
  roof(x, y, z, w, d, h, color, rot = 0) {
    const cs = Math.cos(rot), sn = Math.sin(rot), P = (dx, dy, dz) => [x + dx * cs + dz * sn, y + dy, z - dx * sn + dz * cs];
    const hw = w / 2 + 0.4, hd = d / 2 + 0.4;
    if (w >= d) { const a = P(-hw, 0, -hd), b = P(hw, 0, -hd), c = P(hw, 0, hd), e = P(-hw, 0, hd), r1 = P(-hw, h, 0), r2 = P(hw, h, 0);
      this.quad(a, r1, r2, b, shade(color, 0.85)); this.quad(c, r2, r1, e, color); this.tri(e, r1, a, shade(color, 0.7)); this.tri(b, r2, c, shade(color, 0.7)); }
    else { const a = P(-hw, 0, -hd), b = P(hw, 0, -hd), c = P(hw, 0, hd), e = P(-hw, 0, hd), r1 = P(0, h, -hd), r2 = P(0, h, hd);
      this.quad(e, r2, r1, a, shade(color, 0.85)); this.quad(b, r1, r2, c, color); this.tri(a, r1, b, shade(color, 0.7)); this.tri(c, r2, e, shade(color, 0.7)); }
  }
  mesh(material) {
    const T = this.THREE, g = new T.BufferGeometry();
    g.setAttribute('position', new T.Float32BufferAttribute(this.pos, 3)); g.setAttribute('color', new T.Float32BufferAttribute(this.col, 3));
    g.computeVertexNormals(); g.computeBoundingSphere();
    return new T.Mesh(g, material || new T.MeshLambertMaterial({ vertexColors: true, flatShading: true }));
  }
  get empty() { return this.pos.length === 0; }
}
function shade(hex, k) { const n = parseInt(String(hex).replace('#', ''), 16); const r = ((n >> 16) & 255) * k, g = ((n >> 8) & 255) * k, b = (n & 255) * k; return '#' + [r, g, b].map(v => Math.round(v).toString(16).padStart(2, '0')).join(''); }
const smooth = (a, b, x) => { const t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); };

// distance from point p to segment a-b, and the closest point
function segDist(px, pz, ax, az, bx, bz) {
  const dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz || 1; let t = ((px - ax) * dx + (pz - az) * dz) / l2; t = Math.max(0, Math.min(1, t));
  const cx = ax + dx * t, cz = az + dz * t; return [Math.hypot(px - cx, pz - cz), cx, cz];
}

export function buildWorld(THREE, scene, design = {}) {
  const D = { size: 360, seed: seedOf(design.title || 'world'), hills: 14, water: -2.5, trees: 900, rocks: 160, ...(design.world || {}) };
  const seed = design.seed ?? D.seed, noise = new Noise(seed), r = rng(seed + 7), half = D.size / 2;
  const villages = (design.villages || []).map((v, i) => ({ name: v.name || 'Village ' + (i + 1), x: v.x ?? 0, z: v.z ?? 0, houses: v.houses ?? 6, radius: v.radius ?? 26, floors: v.floors ?? 3, wall: v.wall, roof: v.roof, people: v.people || [] }));
  const dungeonDefs = (design.dungeons || []).map((d, i) => ({ name: d.name || 'Dungeon ' + (i + 1), x: d.x ?? 60 * (i + 1), z: d.z ?? -60, rooms: d.rooms ?? 7, creatures: d.creatures || [], boss: d.boss || null, chests: d.chests ?? 2, theme: d.theme || 'cave', index: i }));

  // ---- terrain: hills from noise, mountains at the edge of the map, flat ground where villages and dungeon doors stand ----
  const flats = [...villages.map(v => ({ x: v.x, z: v.z, r: v.radius + 10 })), ...dungeonDefs.map(d => ({ x: d.x, z: d.z, r: 9 }))];
  const raw = (x, z) => D.hills * noise.fbm(x / 110 + 10, z / 110 - 4, 4) + 4 * noise.at(x / 25, z / 25) * 0.4 + 40 * smooth(0.72, 1.0, Math.max(Math.abs(x), Math.abs(z)) / half);
  for (const f of flats) f.y = Math.max(D.water + 1.5, raw(f.x, f.z));
  const shapeAt = (x, z) => { let h = raw(x, z); for (const f of flats) { const d = Math.hypot(x - f.x, z - f.z); if (d < f.r + 14) h = h + (f.y - h) * (1 - smooth(f.r, f.r + 14, d)); } return h; };
  const seg = Math.min(220, Math.round(D.size / 2)), step = D.size / seg, H = new Float32Array((seg + 1) * (seg + 1));
  for (let j = 0; j <= seg; j++) for (let i = 0; i <= seg; i++) H[j * (seg + 1) + i] = shapeAt(-half + i * step, -half + j * step);
  const heightAt = (x, z) => {   // the same triangles as the mesh, so feet stand on what is drawn
    const fx = (x + half) / step, fz = (z + half) / step, i = Math.max(0, Math.min(seg - 1, Math.floor(fx))), j = Math.max(0, Math.min(seg - 1, Math.floor(fz)));
    const u = Math.max(0, Math.min(1, fx - i)), v = Math.max(0, Math.min(1, fz - j)), a = H[j * (seg + 1) + i], b = H[j * (seg + 1) + i + 1], c = H[(j + 1) * (seg + 1) + i], d = H[(j + 1) * (seg + 1) + i + 1];
    return u + v <= 1 ? a + (b - a) * u + (c - a) * v : d + (c - d) * (1 - u) + (b - d) * (1 - v);
  };
  // roads: from each village to the nearest other, and to each dungeon door
  const roads = [];
  villages.forEach((v, i) => { let best = null, bd = 1e9; villages.forEach((w, k) => { if (k !== i) { const d = Math.hypot(v.x - w.x, v.z - w.z); if (d < bd) { bd = d; best = w; } } }); if (best && !roads.some(rd => rd[2] === v && rd[3] === best)) roads.push([v.x, v.z, best, v, best.x, best.z]); });
  for (const d of dungeonDefs) { let best = null, bd = 1e9; for (const v of villages) { const k = Math.hypot(v.x - d.x, v.z - d.z); if (k < bd) { bd = k; best = v; } } if (best) roads.push([best.x, best.z, null, null, d.x, d.z]); }
  const roadDist = (x, z) => { let m = 1e9; for (const rd of roads) m = Math.min(m, segDist(x, z, rd[0], rd[1], rd[4], rd[5])[0]); return m; };
  {
    const geo = new THREE.PlaneGeometry(D.size, D.size, seg, seg); geo.rotateX(-Math.PI / 2);
    const pos = geo.attributes.position, col = new Float32Array(pos.count * 3), c = new THREE.Color();
    const pal = { grass: '#5f9e48', grass2: '#4f8a3c', dirt: '#8a7350', rock: '#7c7b78', snow: '#eef2f4', sand: '#d8c88c', road: '#9a8462', ...(design.world && design.world.colors || {}) };
    for (let k = 0; k < pos.count; k++) {
      const x = pos.getX(k), z = pos.getZ(k), i = Math.round((x + half) / step), j = Math.round((z + half) / step), h = H[j * (seg + 1) + i]; pos.setY(k, h);
      const hx = H[j * (seg + 1) + Math.min(seg, i + 1)] - H[j * (seg + 1) + Math.max(0, i - 1)], hz = H[Math.min(seg, j + 1) * (seg + 1) + i] - H[Math.max(0, j - 1) * (seg + 1) + i], slope = Math.hypot(hx, hz) / (2 * step);
      let col0 = noise.at(x / 13, z / 13) > 0.1 ? pal.grass2 : pal.grass;
      if (h < D.water + 0.8) col0 = pal.sand; if (slope > 0.75) col0 = pal.rock; if (h > 26) col0 = pal.rock; if (h > 34) col0 = pal.snow;
      if (roadDist(x, z) < 2.2 && h > D.water + 0.5) col0 = pal.road;
      c.set(col0); col[k * 3] = c.r; col[k * 3 + 1] = c.g; col[k * 3 + 2] = c.b;
    }
    geo.setAttribute('color', new THREE.BufferAttribute(col, 3)); geo.computeVertexNormals();
    const ground = new THREE.Mesh(geo, new THREE.MeshLambertMaterial({ vertexColors: true, flatShading: true })); ground.name = 'ground'; scene.add(ground);
    const water = new THREE.Mesh(new THREE.PlaneGeometry(D.size * 1.6, D.size * 1.6), new THREE.MeshLambertMaterial({ color: (design.world && design.world.waterColor) || '#3a78b8', transparent: true, opacity: 0.82 }));
    water.rotation.x = -Math.PI / 2; water.position.y = D.water; water.name = 'water'; scene.add(water);
  }

  // ---- villages: houses round a square, doors facing the middle, 1 to `floors` floors each ----
  const houses = [], walls = [], blockers = [], interactables = [];
  const material = new THREE.MeshLambertMaterial({ vertexColors: true, flatShading: true });
  for (const v of villages) {
    const wallCol = v.wall || '#d8c8a8', roofCol = v.roof || '#8a3a2a', trim = '#5a3a24', plaza = new Batch(THREE);
    const vy = heightAt(v.x, v.z);
    plaza.box(v.x, vy - 0.45, v.z, 7, 0.5, 7, '#a89a80');                       // the square, with a well
    plaza.box(v.x, vy, v.z, 1.6, 0.9, 1.6, '#8a8a8a'); blockers.push({ x: v.x, z: v.z, r: 1.1 });
    scene.add(plaza.mesh(material));
    for (let h = 0; h < v.houses; h++) {
      const ang = (h / v.houses) * Math.PI * 2 + r() * 0.3, dist = v.radius * (0.55 + r() * 0.35);
      const hx = v.x + Math.cos(ang) * dist, hz = v.z + Math.sin(ang) * dist, w = 6 + Math.floor(r() * 3), d = 5 + Math.floor(r() * 3);
      const floors = Math.max(1, Math.min(v.floors, 1 + Math.floor(r() * v.floors + (h === 0 ? v.floors : 0))));
      const rot = Math.atan2(v.x - hx, v.z - hz);   // the front (local +z) faces the square
      const y = heightAt(hx, hz) + 0.05;
      const house = { id: houses.length, village: v.name, x: hx, z: hz, w, d, rot, floors, y, levels: [], roof: null, walls: [] };
      const cs = Math.cos(rot), sn = Math.sin(rot), W = (lx, lz) => [hx + lx * cs + lz * sn, hz - lx * sn + lz * cs];
      for (let k = 0; k < floors; k++) {
        const b = new Batch(THREE), base = y + k * FLOOR_H;
        b.box(hx, base - (k === 0 ? 0.6 : 0.25), hz, w, k === 0 ? 0.65 : 0.25, d, k === 0 ? '#7a6a54' : '#8a6a44', rot);   // floor
        const hw = w / 2, hd = d / 2, door = 1.4;
        // walls: back, left, right whole; front with a doorway on the ground floor and windows above
        const pieces = [[[-hw, -hd], [hw, -hd]], [[-hw, -hd], [-hw, hd]], [[hw, -hd], [hw, hd]]];
        if (k === 0) pieces.push([[-hw, hd], [-door / 2, hd]], [[door / 2, hd], [hw, hd]]); else pieces.push([[-hw, hd], [hw, hd]]);
        for (const [[ax, az], [bx, bz]] of pieces) {
          const len = Math.hypot(bx - ax, bz - az), mx = (ax + bx) / 2, mz = (az + bz) / 2, [wx, wz] = W(mx, mz), wr = rot + (Math.abs(bx - ax) > 0.01 ? 0 : Math.PI / 2);
          b.box(wx, base, wz, len + WALL_T, FLOOR_H - 0.05, WALL_T, wallCol, wr);
          const A = W(ax, az), B = W(bx, bz); const wall = { ax: A[0], az: A[1], bx: B[0], bz: B[1], level: k, house: house.id }; walls.push(wall); house.walls.push(wall);
        }
        if (k === 0) { const [lx, lz] = W(-door / 2 - 0.1, hd + 0.05), [rx2, rz2] = W(door / 2 + 0.1, hd + 0.05); b.box(lx, base, lz, 0.2, 2.3, 0.4, trim, rot); b.box(rx2, base, rz2, 0.2, 2.3, 0.4, trim, rot); }
        // stairs up in the back left corner (floors below the top), the way down in the back right corner (floors above the ground)
        if (k < floors - 1) { const [sx, sz] = W(-hw + 1.1, -hd + 1.1); for (let st = 0; st < 5; st++) { const [tx, tz] = W(-hw + 0.6 + st * 0.35, -hd + 1.1); b.box(tx, base, tz, 0.4, 0.55 * (st + 1), 1.2, '#7a5530', rot); }
          interactables.push({ kind: 'stairsUp', label: 'Climb up', x: sx, z: sz, level: k, house: house.id, to: k + 1, y: base }); }
        if (k > 0) { const [sx, sz] = W(hw - 1.1, -hd + 1.1); b.box(sx, base - 0.2, sz, 1.2, 0.22, 1.2, '#3a2a1a', rot);
          interactables.push({ kind: 'stairsDown', label: 'Climb down', x: sx, z: sz, level: k, house: house.id, to: k - 1, y: base }); }
        // a table and a bed now and then, so rooms are not empty
        if (r() < 0.7) { const [tx, tz] = W(hw * 0.35, hd * 0.1); b.box(tx, base, tz, 1.2, 0.8, 0.8, '#6a4a2a', rot); blockers.push({ x: tx, z: tz, r: 0.6, level: k, house: house.id }); }
        const mesh = b.mesh(material); scene.add(mesh); house.levels.push(mesh);
      }
      const rb = new Batch(THREE); rb.roof(hx, y + floors * FLOOR_H - 0.05, hz, w, d, Math.min(w, d) * 0.55, roofCol, rot); house.roof = rb.mesh(material); scene.add(house.roof);
      house.door = W(0, d / 2 + 1.2);
      houses.push(house);
    }
  }
  const houseAt = (x, z) => houses.find(h => { const dx = x - h.x, dz = z - h.z, cs = Math.cos(h.rot), sn = Math.sin(h.rot), lx = dx * cs - dz * sn, lz = dx * sn + dz * cs; return Math.abs(lx) < h.w / 2 && Math.abs(lz) < h.d / 2; }) || null;

  // ---- dungeons: a grid of rooms and corridors far away from the surface, reached through a door in the ground ----
  const dungeons = [];
  for (const dd of dungeonDefs) {
    const N = 26, grid = new Uint8Array(N * N), ox = 4000 + dd.index * 200, oz = 4000, dr = rng(seed + 101 + dd.index), rooms = [];
    for (let t = 0; rooms.length < dd.rooms && t < 300; t++) {
      const w = 3 + Math.floor(dr() * 4), h = 3 + Math.floor(dr() * 4), x = 1 + Math.floor(dr() * (N - w - 2)), z = 1 + Math.floor(dr() * (N - h - 2));
      if (rooms.some(o => x < o.x + o.w + 1 && x + w + 1 > o.x && z < o.z + o.h + 1 && z + h + 1 > o.z)) continue;
      rooms.push({ x, z, w, h });
    }
    rooms.sort((a, b) => (a.x + a.z) - (b.x + b.z));
    for (const rm of rooms) for (let i = rm.x; i < rm.x + rm.w; i++) for (let j = rm.z; j < rm.z + rm.h; j++) grid[j * N + i] = 1;
    for (let k = 1; k < rooms.length; k++) {   // L-shaped corridors joining each room to the one before
      const a = rooms[k - 1], b = rooms[k], ax = a.x + (a.w >> 1), az = a.z + (a.h >> 1), bx = b.x + (b.w >> 1), bz = b.z + (b.h >> 1);
      for (let i = Math.min(ax, bx); i <= Math.max(ax, bx); i++) grid[az * N + i] = 1;
      for (let j = Math.min(az, bz); j <= Math.max(az, bz); j++) grid[j * N + bx] = 1;
    }
    const cellX = i => ox + (i + 0.5) * CELL, cellZ = j => oz + (j + 0.5) * CELL;
    const b = new Batch(THREE), floorCol = dd.theme === 'crypt' ? '#4a4652' : '#3e3a34', wallCol = dd.theme === 'crypt' ? '#5e5a66' : '#55504a';
    for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
      if (grid[j * N + i]) { b.box(cellX(i), -0.3, cellZ(j), CELL, 0.3, CELL, (i + j) % 2 ? floorCol : shade(floorCol, 0.9)); b.box(cellX(i), 3.6, cellZ(j), CELL, 0.3, CELL, '#2a2622'); continue; }
      const nearFloor = [[1, 0], [-1, 0], [0, 1], [0, -1]].some(([di, dj]) => { const a = i + di, c = j + dj; return a >= 0 && c >= 0 && a < N && c < N && grid[c * N + a]; });
      if (nearFloor) b.box(cellX(i), 0, cellZ(j), CELL, 3.6, CELL, (i * 7 + j * 3) % 5 === 0 ? shade(wallCol, 0.85) : wallCol);
    }
    const torches = [];
    rooms.forEach((rm, k) => { const tx = cellX(rm.x) - CELL / 2 + 0.2, tz = cellZ(rm.z + (rm.h >> 1)); b.box(tx, 1.6, tz, 0.15, 0.5, 0.15, '#5a3a1a'); torches.push([tx + 0.25, 2.2, tz]); });
    const mesh = b.mesh(material); mesh.visible = false; scene.add(mesh);
    const flames = new THREE.Group(); for (const [x, y, z] of torches) { const f = new THREE.Mesh(new THREE.OctahedronGeometry(0.16), new THREE.MeshBasicMaterial({ color: '#ffb040' })); f.position.set(x, y, z); flames.add(f); }
    flames.visible = false; scene.add(flames);
    const start = rooms[0], last = rooms[rooms.length - 1];
    const dungeon = { name: dd.name, index: dd.index, def: dd, grid, N, ox, oz, rooms, mesh, flames, torches,
      start: { x: cellX(start.x + (start.w >> 1)), z: cellZ(start.z + (start.h >> 1)) },
      bossRoom: last, walkable: (x, z) => { const i = Math.floor((x - ox) / CELL), j = Math.floor((z - oz) / CELL); return i >= 0 && j >= 0 && i < N && j < N && grid[j * N + i] === 1; },
      roomSpot: (rm, k = 0) => ({ x: cellX(rm.x) - CELL / 2 + (0.5 + ((k * 0.618) % 1) * (rm.w - 1)) * CELL, z: cellZ(rm.z) - CELL / 2 + (0.5 + ((k * 0.382 + 0.3) % 1) * (rm.h - 1)) * CELL }) };
    // the door in the ground on the surface, the way out inside, and chests in the rooms
    const gy = heightAt(dd.x, dd.z), door = new Batch(THREE);
    door.box(dd.x, gy - 0.1, dd.z, 4.5, 2.6, 1.2, '#6a6660'); door.box(dd.x, gy - 0.1, dd.z + 0.45, 2, 2.1, 0.4, '#101010'); door.roof(dd.x, gy + 2.5, dd.z, 4.5, 1.2, 0.8, '#5a5650');
    scene.add(door.mesh(material)); blockers.push({ x: dd.x - 1.6, z: dd.z, r: 0.9 }, { x: dd.x + 1.6, z: dd.z, r: 0.9 });
    interactables.push({ kind: 'enterDungeon', label: 'Enter ' + dd.name, x: dd.x, z: dd.z + 1.4, dungeon: dd.index, level: 0 });
    interactables.push({ kind: 'leaveDungeon', label: 'Climb out', x: dungeon.start.x, z: dungeon.start.z - 0.01, dungeon: dd.index, inside: true, level: 0 });
    { const ex = new Batch(THREE); ex.box(dungeon.start.x, 0, dungeon.start.z, 1, 0.15, 1, '#a08a50'); const exm = ex.mesh(material); exm.visible = false; scene.add(exm); dungeon.extra = [exm]; }
    for (let k = 0; k < dd.chests && k < rooms.length - 1; k++) {
      const rm = rooms[1 + Math.floor(dr() * (rooms.length - 1))], p = dungeon.roomSpot(rm, k + 3), cb = new Batch(THREE);
      cb.box(p.x, 0, p.z, 1.0, 0.7, 0.7, '#7a5020'); cb.box(p.x, 0.7, p.z, 1.05, 0.15, 0.75, '#c8a040'); const cm = cb.mesh(material); cm.visible = false; scene.add(cm); dungeon.extra.push(cm);
      interactables.push({ kind: 'chest', label: 'Open chest', x: p.x, z: p.z, dungeon: dd.index, inside: true, level: 0, id: 'chest:' + dd.index + ':' + k, mesh: cm });
    }
    dungeons.push(dungeon);
  }

  // ---- trees and rocks (instanced: thousands cost one draw call each) ----
  const trees = [], rocks = [];
  const busy = (x, z, pad = 0) => villages.some(v => Math.hypot(x - v.x, z - v.z) < v.radius + 6 + pad) || dungeonDefs.some(d => Math.hypot(x - d.x, z - d.z) < 7 + pad) || roadDist(x, z) < 3 + pad;
  for (let t = 0; t < D.trees * 3 && trees.length < D.trees; t++) {
    const x = (r() * 2 - 1) * half * 0.92, z = (r() * 2 - 1) * half * 0.92, y = heightAt(x, z);
    if (y < D.water + 0.6 || y > 25 || busy(x, z) || noise.at(x / 40 + 30, z / 40) < -0.15) continue;
    trees.push({ x, z, y, s: 0.8 + r() * 0.7, pine: noise.at(x / 60, z / 60 + 9) > 0.1 || y > 14 });
  }
  for (let t = 0; t < D.rocks * 3 && rocks.length < D.rocks; t++) {
    const x = (r() * 2 - 1) * half * 0.9, z = (r() * 2 - 1) * half * 0.9, y = heightAt(x, z);
    if (y < D.water + 0.3 || busy(x, z)) continue; rocks.push({ x, z, y, s: 0.5 + r() * 1.3 });
  }
  const m4 = new THREE.Matrix4(), q = new THREE.Quaternion(), up = new THREE.Vector3(0, 1, 0), sc = new THREE.Vector3(), at = new THREE.Vector3();
  const inst = (geo, color, list, place) => { const im = new THREE.InstancedMesh(geo, new THREE.MeshLambertMaterial({ color, flatShading: true }), Math.max(1, list.length)); list.forEach((o, i) => { place(o, at, sc); q.setFromAxisAngle(up, (o.x * 13.37) % 6.28); m4.compose(at, q, sc); im.setMatrixAt(i, m4); }); im.count = list.length; scene.add(im); return im; };
  const pines = trees.filter(t => t.pine), leafy = trees.filter(t => !t.pine);
  inst(new THREE.CylinderGeometry(0.18, 0.28, 2, 5), '#6a4a2a', trees, (o, p, s) => { p.set(o.x, o.y + o.s, o.z); s.setScalar(o.s); });
  inst(new THREE.ConeGeometry(1.4, 3.6, 6), '#2f6b3a', pines, (o, p, s) => { p.set(o.x, o.y + o.s * 3.2, o.z); s.setScalar(o.s); });
  inst(new THREE.IcosahedronGeometry(1.6, 0), '#4f8f3a', leafy, (o, p, s) => { p.set(o.x, o.y + o.s * 3, o.z); s.setScalar(o.s); });
  inst(new THREE.DodecahedronGeometry(0.8, 0), '#8a8780', rocks, (o, p, s) => { p.set(o.x, o.y + o.s * 0.3, o.z); s.set(o.s, o.s * 0.7, o.s); });
  for (const t of trees) blockers.push({ x: t.x, z: t.z, r: 0.35 * t.s });
  for (const k of rocks) blockers.push({ x: k.x, z: k.z, r: 0.75 * k.s });
  // a coarse grid of blockers so collision checks look only nearby
  const bgrid = new Map(), gk = (x, z) => Math.floor(x / 8) + ',' + Math.floor(z / 8);
  for (const b of blockers) { const k = gk(b.x, b.z); if (!bgrid.has(k)) bgrid.set(k, []); bgrid.get(k).push(b); }

  // push p ({x, z}) out of whatever it walks into. where: { level, house, dungeon } (house: the house the walker is inside, or null)
  function collide(p, rad = 0.4, where = {}) {
    if (where.dungeon != null) {   // inside: stay on floor cells
      const dg = dungeons[where.dungeon]; if (!dg) return p;
      const cell = (v, o) => Math.floor((v - o) / CELL);
      if (!dg.walkable(p.x + rad, p.z)) p.x = dg.ox + cell(p.x + rad, dg.ox) * CELL - rad - 0.001;          // the cell to the east is rock: stop at its edge
      if (!dg.walkable(p.x - rad, p.z)) p.x = dg.ox + (cell(p.x - rad, dg.ox) + 1) * CELL + rad + 0.001;
      if (!dg.walkable(p.x, p.z + rad)) p.z = dg.oz + cell(p.z + rad, dg.oz) * CELL - rad - 0.001;
      if (!dg.walkable(p.x, p.z - rad)) p.z = dg.oz + (cell(p.z - rad, dg.oz) + 1) * CELL + rad + 0.001;
      return p;
    }
    const level = where.level || 0;
    for (const w of walls) {
      if (w.level !== level) continue;
      if (level > 0 && w.house !== where.house) continue;
      const [d, cx, cz] = segDist(p.x, p.z, w.ax, w.az, w.bx, w.bz), min = rad + WALL_T / 2;
      if (d < min && d > 1e-6) { p.x = cx + (p.x - cx) / d * min; p.z = cz + (p.z - cz) / d * min; }
    }
    const i = Math.floor(p.x / 8), j = Math.floor(p.z / 8);
    for (let a = i - 1; a <= i + 1; a++) for (let c = j - 1; c <= j + 1; c++) for (const b of bgrid.get(a + ',' + c) || []) {
      if ((b.level || 0) !== level || (b.house != null && b.house !== where.house && level > 0)) continue;
      const dx = p.x - b.x, dz = p.z - b.z, d = Math.hypot(dx, dz), min = rad + b.r; if (d < min && d > 1e-6) { p.x = b.x + dx / d * min; p.z = b.z + dz / d * min; }
    }
    const lim = half * 0.96; p.x = Math.max(-lim, Math.min(lim, p.x)); p.z = Math.max(-lim, Math.min(lim, p.z));
    return p;
  }
  // n free spots in an area ({ village } or { x, z, r }), on land, outside houses
  function spots(n, area = {}, sr = rng(seed + 33 + n)) {
    const out = []; const c = area.village ? villages.find(v => v.name === area.village) : null;
    const cx = c ? c.x : area.x ?? 0, cz = c ? c.z : area.z ?? 0, rr = c ? c.radius * 0.9 : area.r ?? half * 0.8;
    for (let t = 0; t < n * 40 && out.length < n; t++) {
      const a = sr() * Math.PI * 2, d = Math.sqrt(sr()) * rr, x = cx + Math.cos(a) * d, z = cz + Math.sin(a) * d, y = heightAt(x, z);
      if (y < D.water + 0.5 || houseAt(x, z) || Math.abs(x) > half * 0.9 || Math.abs(z) > half * 0.9) continue;
      const p = collide({ x, z }, 0.5); if (Math.hypot(p.x - x, p.z - z) > 0.01) continue;
      out.push({ x, z });
    }
    return out;
  }
  return { size: D.size, water: D.water, heightAt, collide, spots, villages, houses, houseAt, dungeons, trees, rocks, interactables, walls, roads, FLOOR_H };
}
