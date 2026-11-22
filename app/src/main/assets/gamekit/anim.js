// Plays animations exported from a game cache (game_cache export kind=anims) on models with vertex groups (kind=player,
// kind=npcs3d, kind=models). A building block for any game: give it a model JSON and a sequence JSON and call update() every frame.
// Model JSON: { vertices: [x,y,z,...] (y up, 128 units a tile), faces: [a,b,c,...], colors: ["#rrggbb" per face], groups: [group per vertex] }
// Sequence JSON: { lengths: [ticks of 20 ms per frame], loopFrom, skeletons: { id: { types, labels } }, frames: [{ skeleton, moves: [[transform, x, y, z]] }] }
// Transform types follow the game: 0 sets the pivot, 1 moves, 2 turns (2048ths of a circle, z then x then y), 3 scales (128 = same).
// Written for PocketIDE's game kit; MIT licensed.

const SIN = new Float64Array(2048), COS = new Float64Array(2048);
for (let i = 0; i < 2048; i++) { SIN[i] = Math.sin(i * Math.PI / 1024); COS[i] = Math.cos(i * Math.PI / 1024); }

// Moves x, y, z (the model's own axes: y DOWN, as stored in the cache) to one frame. byGroup: Map group -> Int32Array of vertices.
export function applyFrame(x, y, z, byGroup, skel, frame) {
  let ox = 0, oy = 0, oz = 0;
  for (const m of frame.moves) {
    const t = m[0], type = skel.types[t], labels = skel.labels[t] || [];
    if (type === 0) {
      let n = 0, sx = 0, sy = 0, sz = 0;
      for (const l of labels) { const vs = byGroup.get(l); if (vs) for (const v of vs) { sx += x[v]; sy += y[v]; sz += z[v]; n++; } }
      if (n) { ox = Math.trunc(sx / n) + m[1]; oy = Math.trunc(sy / n) + m[2]; oz = Math.trunc(sz / n) + m[3]; } else { ox = m[1]; oy = m[2]; oz = m[3]; }
      continue;
    }
    for (const l of labels) {
      const vs = byGroup.get(l); if (!vs) continue;
      if (type === 1) { for (const v of vs) { x[v] += m[1]; y[v] += m[2]; z[v] += m[3]; } }
      else if (type === 2) {
        const ax = m[1] & 2047, ay = m[2] & 2047, az = m[3] & 2047;
        for (const v of vs) {
          let px = x[v] - ox, py = y[v] - oy, pz = z[v] - oz, t2;
          if (az) { t2 = py * SIN[az] + px * COS[az]; py = py * COS[az] - px * SIN[az]; px = t2; }
          if (ax) { t2 = py * COS[ax] - pz * SIN[ax]; pz = py * SIN[ax] + pz * COS[ax]; py = t2; }
          if (ay) { t2 = pz * SIN[ay] + px * COS[ay]; pz = pz * COS[ay] - px * SIN[ay]; px = t2; }
          x[v] = px + ox; y[v] = py + oy; z[v] = pz + oz;
        }
      } else if (type === 3) {
        for (const v of vs) { x[v] = (x[v] - ox) * m[1] / 128 + ox; y[v] = (y[v] - oy) * m[2] / 128 + oy; z[v] = (z[v] - oz) * m[3] / 128 + oz; }
      }
    }
  }
}

const seqCache = new Map();
// Loads data/anim/<id>.json once; resolves to null when the game has no such animation.
export function loadSeq(id, base = 'data/anim/') {
  if (id == null || id < 0) return Promise.resolve(null);
  if (!seqCache.has(id)) seqCache.set(id, fetch(base + id + '.json').then(r => r.ok ? r.json() : null).catch(() => null));
  return seqCache.get(id);
}

// A model that can play sequences. THREE: the three.js module. opts: { scaleXZ, scaleY } (128 = the model's own size).
export class AnimatedModel {
  constructor(THREE, json, opts = {}) {
    const v = json.vertices, f = json.faces, n = v.length / 3;
    this.n = n;
    this.bx = new Float64Array(n); this.by = new Float64Array(n); this.bz = new Float64Array(n);
    for (let i = 0; i < n; i++) { this.bx[i] = v[i * 3]; this.by[i] = -v[i * 3 + 1]; this.bz[i] = v[i * 3 + 2]; }
    this.x = new Float64Array(n); this.y = new Float64Array(n); this.z = new Float64Array(n);
    this.byGroup = new Map();
    if (json.groups) { const tmp = new Map(); json.groups.forEach((g, i) => { if (!tmp.has(g)) tmp.set(g, []); tmp.get(g).push(i); }); for (const [g, l] of tmp) this.byGroup.set(g, Int32Array.from(l)); }
    this.corners = Uint32Array.from(f);
    const pos = new Float32Array(f.length * 3), col = new Float32Array(f.length * 3);
    const c = new THREE.Color();
    for (let k = 0; k < f.length / 3; k++) { c.set(json.colors[k] || '#cccccc'); for (let j = 0; j < 3; j++) { col[(k * 3 + j) * 3] = c.r; col[(k * 3 + j) * 3 + 1] = c.g; col[(k * 3 + j) * 3 + 2] = c.b; } }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
    this.geo = geo;
    this.mesh = new THREE.Mesh(geo, new THREE.MeshLambertMaterial({ vertexColors: true, side: THREE.DoubleSide, flatShading: true }));
    const sxz = (opts.scaleXZ || 128) / 128 / 128, sy = (opts.scaleY || 128) / 128 / 128;
    this.mesh.scale.set(sxz, sy, sxz);
    this.seq = null; this.time = 0; this.frame = -2;
    this.pose(-1);
  }
  // play a sequence (from loadSeq); loop: start again at the end; onEnd: called when a non-looping one finishes
  play(seq, { loop = true, onEnd = null, restart = false } = {}) {
    if (seq === this.seq && !restart) return;
    if (!seq) { this.seq = null; this.pose(-1); return; }
    this.seq = seq; this.time = 0; this.loop = loop; this.onEnd = onEnd; this.frame = -2;
    this.update(0);
  }
  get playing() { return this.seq; }
  update(dt) {
    const s = this.seq; if (!s || !s.frames.length) return;
    this.time += dt;
    let ticks = this.time / 20, k = 0, total = 0;
    for (const l of s.lengths) total += Math.max(1, l);
    if (ticks >= total) {
      if (this.loop) { const from = s.loopFrom > 0 && s.loopFrom < s.frames.length ? s.lengths.slice(0, s.loopFrom).reduce((a, b) => a + Math.max(1, b), 0) : 0; ticks = from + (ticks - total) % Math.max(1, total - from); this.time = ticks * 20; }
      else { const end = this.onEnd; this.seq = null; this.pose(-1); if (end) end(); return; }
    }
    for (let acc = 0; k < s.frames.length; k++) { acc += Math.max(1, s.lengths[k]); if (ticks < acc) break; }
    if (k !== this.frame) this.pose(k);
  }
  // shows frame k of the current sequence (-1: the model as stored)
  pose(k) {
    this.frame = k;
    const { x, y, z } = this; x.set(this.bx); y.set(this.by); z.set(this.bz);
    const s = this.seq, f = s && k >= 0 ? s.frames[k] : null;
    if (f) { const skel = s.skeletons[f.skeleton]; if (skel) applyFrame(x, y, z, this.byGroup, skel, f); }
    const pos = this.geo.attributes.position.array, cs = this.corners;
    // three.js axes: y up, z towards the viewer (south); the cache has y down and z to the north
    for (let i = 0; i < cs.length; i++) { const v = cs[i]; pos[i * 3] = x[v]; pos[i * 3 + 1] = -y[v]; pos[i * 3 + 2] = -z[v]; }
    this.geo.attributes.position.needsUpdate = true;
    this.geo.computeBoundingSphere();
  }
}
