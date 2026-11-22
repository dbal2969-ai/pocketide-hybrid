// The 3D view: map squares baked by game_cache export kind=scene (ground with real heights, every object as its real model),
// characters as animated models (anim.js), a camera that orbits the player like the game's (drag to turn, pinch or wheel to zoom),
// taps turned into tiles. A building block for any 3D tile game: it only needs the baked squares and tile coordinates.
// World tiles: x east, z north. three.js: X east, Y up, Z south (so Z = -z). Written for PocketIDE's game kit; MIT licensed.
import * as THREE from './engine/three.module.min.js';
import { AnimatedModel, loadSeq } from './anim.js';

async function gunzip(buf) {
  const s = new Blob([buf]).stream().pipeThrough(new DecompressionStream('gzip'));
  return await new Response(s).arrayBuffer();
}

// Reads one baked square (see Scene3D.kt for the layout).
export function parseSquare(ab) {
  const d = new DataView(ab); let o = 0;
  const magic = String.fromCharCode(d.getUint8(0), d.getUint8(1), d.getUint8(2), d.getUint8(3)); o = 4;
  if (magic !== 'PS3D') throw new Error('not a baked square');
  const version = d.getUint16(o, true), sx = d.getInt16(o + 2, true), sz = d.getInt16(o + 4, true); o += 6;
  if (version !== 2 && version !== 3) throw new Error('baked square version ' + version + ' is not supported; export kind=scene again');
  const heights = new Int16Array(65 * 65); for (let i = 0; i < heights.length; i++) { heights[i] = d.getInt16(o, true); o += 2; }
  const chunks = [], n = d.getUint16(o, true); o += 2;
  for (let i = 0; i < n; i++) {
    const section = d.getUint8(o), chunk = d.getUint8(o + 1), vc = d.getUint32(o + 2, true), ic = d.getUint32(o + 6, true), isz = d.getUint8(o + 10); o += 11;
    const pos = new Int16Array(ab.slice(o, o + vc * 6)); o += vc * 6;
    const col = new Uint8Array(ab.slice(o, o + vc * 3)); o += vc * 3;
    const idx = isz === 4 ? new Uint32Array(ab.slice(o, o + ic * 4)) : new Uint16Array(ab.slice(o, o + ic * 2)); o += ic * isz;
    // the floor a chunk belongs to: version 3 bakes floors 1-3 as sections 2-4; version 2 put all upper floors in section 2
    chunks.push({ section, chunk, pos, col, idx, mesh: null, plane: section <= 1 ? 0 : version >= 3 ? section - 1 : 1 });
  }
  const ranges = [], rc = d.getUint32(o, true); o += 4;
  for (let i = 0; i < rc; i++) { ranges.push({ id: d.getInt32(o, true), x: d.getUint8(o + 4), z: d.getUint8(o + 5), plane: d.getUint8(o + 6), section: d.getUint8(o + 7), chunk: d.getUint8(o + 8), first: d.getUint32(o + 9, true), count: d.getUint32(o + 13, true) }); o += 17; }
  return { sx, sz, heights, chunks, ranges };
}

export class View3D {
  // opts: { drawDistance (tiles, default 40), sky ('#rrggbb') }
  constructor(canvas, opts = {}) {
    this.canvas = canvas;
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: false, powerPreference: 'high-performance' });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.scene = new THREE.Scene();
    const sky = new THREE.Color(opts.sky || '#8fb3d0');
    this.scene.background = sky;
    this.drawDistance = opts.drawDistance || 40;
    this.scene.fog = new THREE.Fog(sky, this.drawDistance * 0.6, this.drawDistance * 1.05);
    this.camera = new THREE.PerspectiveCamera(50, 1, 0.1, 400);
    this.scene.add(new THREE.HemisphereLight(0xffffff, 0x445533, 1.1));
    const sun = new THREE.DirectionalLight(0xffffff, 1.4); sun.position.set(-0.45, 0.75, -0.48); this.scene.add(sun);
    this.material = new THREE.MeshBasicMaterial({ vertexColors: true, side: THREE.DoubleSide });
    this.squares = new Map(); this.hidden = new Set(); this.actors = new Set();
    this.yaw = 0; this.pitch = 0.75; this.zoom = 12;          // yaw 0: looking north
    this.target = new THREE.Vector3(); this.roofHidden = false; this.plane = 0;
    this.ray = new THREE.Raycaster();
    this.onTap = () => {};
    this.input();
  }

  resize(w, h) { this.renderer.setSize(w, h, false); this.camera.aspect = w / h; this.camera.updateProjectionMatrix(); }

  // squares: ["50,50", ...]; base: the folder holding s<x>_<z>.bin.gz
  async load(squares, base = 'data/scene/') {
    await Promise.all(squares.map(async key => {
      const [x, z] = key.split(',').map(Number);
      try {
        const r = await fetch(base + 's' + x + '_' + z + '.bin.gz'); if (!r.ok) return;
        this.squares.set(x + ',' + z, parseSquare(await gunzip(await r.arrayBuffer())));
      } catch (e) { console.warn('square ' + key + ': ' + e.message); }
    }));
    return this.squares.size;
  }

  // ground height (in tiles) at a world point, from the corner heights of floor 0
  heightAt(x, z) {
    const sq = this.squares.get(Math.floor(x / 64) + ',' + Math.floor(z / 64)); if (!sq) return 0;
    const lx = x - sq.sx * 64, lz = z - sq.sz * 64, ix = Math.min(63, Math.floor(lx)), iz = Math.min(63, Math.floor(lz)), fx = lx - ix, fz = lz - iz, H = sq.heights;
    const h00 = H[ix * 65 + iz], h10 = H[(ix + 1) * 65 + iz], h01 = H[ix * 65 + iz + 1], h11 = H[(ix + 1) * 65 + iz + 1];
    return ((h00 * (1 - fx) + h10 * fx) * (1 - fz) + (h01 * (1 - fx) + h11 * fx) * fz) / 128;
  }

  // keeps meshes only for chunks near (px, pz): the phone draws what is in sight
  updateChunks(px, pz) {
    const keep = this.drawDistance + 12, drop = this.drawDistance + 24;
    for (const sq of this.squares.values()) for (const c of sq.chunks) {
      const cx = sq.sx * 64 + ((c.chunk >> 2) * 16) + 8, cz = sq.sz * 64 + ((c.chunk & 3) * 16) + 8, d = Math.hypot(cx - px, cz - pz);
      if (!c.mesh && d <= keep) this.makeMesh(sq, c);
      else if (c.mesh && d > drop) { this.scene.remove(c.mesh); c.mesh.geometry.dispose(); c.mesh = null; }
      if (c.mesh) c.mesh.visible = this.floorShown(c);
    }
  }
  makeMesh(sq, c) {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(c.pos, 3));
    g.setAttribute('color', new THREE.BufferAttribute(c.col, 3, true));
    c.shown = c.idx.slice();                                         // a copy: objects that are cut down get their triangles emptied here
    g.setIndex(new THREE.BufferAttribute(c.shown, 1));
    g.computeBoundingSphere();
    const m = new THREE.Mesh(g, this.material);
    m.position.set(sq.sx * 64, 0, -sq.sz * 64); m.scale.set(1 / 128, 1 / 128, -1 / 128);
    m.userData = { section: c.section, sq, c };
    c.mesh = m; this.scene.add(m);
    for (const r of sq.ranges) if (r.chunk === c.chunk && r.section === c.section && this.hidden.has(this.key(sq, r))) this.setRange(c, r, false);
  }
  key(sq, r) { return (sq.sx * 64 + r.x) + ',' + (sq.sz * 64 + r.z) + ',' + r.plane + ',' + r.id; }
  setRange(c, r, show) {
    for (let i = r.first; i < r.first + r.count; i++) c.shown[i] = show ? c.idx[i] : 0;
    if (c.mesh) c.mesh.geometry.index.needsUpdate = true;
  }
  // hides or shows the object of id at world tile (x, z) on floor 0 (a tree that was cut down, then grows back)
  showObject(id, x, z, show) {
    const sq = this.squares.get((x >> 6) + ',' + (z >> 6)); if (!sq) return;
    for (const r of sq.ranges) if (r.id === id && sq.sx * 64 + r.x === x && sq.sz * 64 + r.z === z && r.plane === 0) {
      const k = this.key(sq, r); if (show) this.hidden.delete(k); else this.hidden.add(k);
      const c = sq.chunks.find(c => c.chunk === r.chunk && c.section === r.section); if (c && c.shown) this.setRange(c, r, show);
    }
  }
  // floors up to the player's are always drawn; the ones above (upper rooms, roofs) only while the player is not under a roof
  floorShown(c) { return c.plane <= (this.plane | 0) || !this.roofHidden; }
  setRoofHidden(h) { this.roofHidden = h; for (const sq of this.squares.values()) for (const c of sq.chunks) if (c.mesh) c.mesh.visible = this.floorShown(c); }
  // the floor the player is on (0-3): game code calls it after a ladder or staircase
  setPlane(p) { this.plane = p | 0; this.setRoofHidden(this.roofHidden); }

  // ---- characters ----
  // json: a model with vertex groups; opts: { scaleXZ, scaleY, size }. Returns an actor to move and animate.
  addActor(json, opts = {}) {
    const model = new AnimatedModel(THREE, json, opts);
    const holder = new THREE.Group(); holder.add(model.mesh); this.scene.add(holder);
    const a = { model, holder, size: opts.size || 1, x: 0, z: 0, angle: 0, anims: {}, userData: opts.userData };
    model.mesh.userData.actor = a;
    this.actors.add(a); return a;
  }
  // an item in the actor's hand (held_<id>.json): it moves with the actor's animation; null takes it away
  hold(a, json) {
    if (a.held) { a.holder.remove(a.held.mesh); a.held.geo.dispose(); a.held = null; }
    if (!json) return;
    const m = new AnimatedModel(THREE, json, {}); a.held = m; a.holder.add(m.mesh);
    if (a.model.seq) { m.play(a.model.seq, { loop: a.model.loop }); m.time = a.model.time; m.update(0); }
  }
  removeActor(a) { this.scene.remove(a.holder); a.model.geo.dispose(); this.actors.delete(a); }
  // place at world tile (x, z) (floats allowed), facing angle (radians, 0 = south as the game draws models)
  placeActor(a, x, z, angle) {
    const c = a.size / 2;
    a.x = x; a.z = z; if (angle != null) a.angle = angle;
    a.holder.position.set(x + c, this.heightAt(x + c, z + c), -(z + c));
    a.holder.rotation.y = a.angle;
  }
  // turn to face a direction given in tiles (dx east, dz north)
  static facing(dx, dz) { return Math.atan2(dx, -dz); }

  // ---- input: drag turns the camera, two fingers or the wheel zoom, a tap picks ----
  input() {
    const el = this.canvas, pts = new Map(); let drag = null, pinch = 0;
    el.style.touchAction = 'none';
    el.addEventListener('pointerdown', e => { pts.set(e.pointerId, { x: e.clientX, y: e.clientY, sx: e.clientX, sy: e.clientY, moved: false }); el.setPointerCapture(e.pointerId);
      if (pts.size === 2) { const [a, b] = [...pts.values()]; pinch = Math.hypot(a.x - b.x, a.y - b.y); } });
    el.addEventListener('pointermove', e => {
      const p = pts.get(e.pointerId); if (!p) return;
      const dx = e.clientX - p.x, dy = e.clientY - p.y; p.x = e.clientX; p.y = e.clientY;
      if (Math.hypot(p.x - p.sx, p.y - p.sy) > 10) p.moved = true;
      if (pts.size === 2) { const [a, b] = [...pts.values()]; const d = Math.hypot(a.x - b.x, a.y - b.y); if (pinch) this.zoomBy(pinch / d); pinch = d; return; }
      if (p.moved) { this.yaw -= dx * 0.008; this.pitch = Math.max(0.3, Math.min(1.35, this.pitch + dy * 0.006)); }
    });
    const up = e => {
      const p = pts.get(e.pointerId); pts.delete(e.pointerId); if (!p) return;
      if (!p.moved && pts.size === 0 && !pinch) this.tap(e.clientX, e.clientY);
      if (pts.size === 0) pinch = 0;
    };
    el.addEventListener('pointerup', up); el.addEventListener('pointercancel', e => pts.delete(e.pointerId));
    el.addEventListener('wheel', e => { e.preventDefault(); this.zoomBy(e.deltaY > 0 ? 1.1 : 0.9); }, { passive: false });
  }
  zoomBy(f) { this.zoom = Math.max(3, Math.min(30, this.zoom * f)); }

  // the tile (and character, if one was tapped) under a point of the screen
  pick(clientX, clientY) {
    const r = this.canvas.getBoundingClientRect();
    const v = new THREE.Vector2(((clientX - r.left) / r.width) * 2 - 1, -((clientY - r.top) / r.height) * 2 + 1);
    this.ray.setFromCamera(v, this.camera);
    const actors = [...this.actors].filter(a => a.holder.visible).map(a => a.model.mesh);
    const hitA = this.ray.intersectObjects(actors, false)[0];
    const ground = [];
    for (const sq of this.squares.values()) for (const c of sq.chunks) if (c.mesh && c.section === 0) ground.push(c.mesh);
    const objects = [];
    for (const sq of this.squares.values()) for (const c of sq.chunks) if (c.mesh && c.section === 1) objects.push(c.mesh);
    const hitG = this.ray.intersectObjects(ground, false)[0], hitO = this.ray.intersectObjects(objects, false)[0];
    // an object in front of the ground (a tree, a booth) gives the tile at its foot
    let p = hitG ? hitG.point : null;
    if (hitO && (!hitG || hitO.distance < hitG.distance)) p = hitO.point;
    const tile = p ? [Math.floor(p.x), Math.floor(-p.z)] : null;
    return { tile, actor: hitA ? hitA.object.userData.actor : this.nearActor(clientX - r.left, clientY - r.top) };
  }
  // a finger is wider than a rat: when no model is under the tap, the character (not the player) whose middle is closest on
  // screen, within [radius] pixels, counts as tapped
  nearActor(x, y, radius = 32) {
    let best = null, bestD = radius;
    for (const a of this.actors) {
      if (!a.holder.visible || !a.userData || !Object.keys(a.userData).length) continue;
      const m = a.model.mesh; if (!m.geometry.boundingBox) m.geometry.computeBoundingBox();
      const bb = m.geometry.boundingBox, mid = (bb.min.y + bb.max.y) / 2 * m.scale.y;
      const v = new THREE.Vector3(a.holder.position.x, a.holder.position.y + mid, a.holder.position.z).project(this.camera);
      if (v.z > 1) continue;
      const d = Math.hypot((v.x + 1) / 2 * this.canvas.clientWidth - x, (1 - v.y) / 2 * this.canvas.clientHeight - y);
      if (d < bestD) { bestD = d; best = a; }
    }
    return best;
  }
  // a picture standing on a tile that always faces the camera (an item on the ground); removeSprite takes it away
  addSprite(url, x, z, size = 0.55) {
    const tex = new THREE.TextureLoader().load(url);
    const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, transparent: true, depthWrite: false }));
    s.scale.set(size, size, 1); s.position.set(x + 0.5, this.heightAt(x + 0.5, z + 0.5) + size * 0.45, -(z + 0.5));
    this.scene.add(s); return s;
  }
  removeSprite(s) { if (!s) return; this.scene.remove(s); if (s.material.map) s.material.map.dispose(); s.material.dispose(); }
  // screen position of an actor's middle (for tests and pointers)
  actorPoint(a) {
    const m = a.model.mesh; if (!m.geometry.boundingBox) m.geometry.computeBoundingBox();
    const bb = m.geometry.boundingBox, mid = (bb.min.y + bb.max.y) / 2 * m.scale.y;
    const v = new THREE.Vector3(a.holder.position.x, a.holder.position.y + mid, a.holder.position.z).project(this.camera);
    return v.z > 1 ? null : [(v.x + 1) / 2 * this.canvas.clientWidth, (1 - v.y) / 2 * this.canvas.clientHeight];
  }
  tap(x, y) { const h = this.pick(x, y); if (h.tile || h.actor) this.onTap(h); }

  // screen position (pixels in the canvas) of a world point; y in tiles above the ground there when ground is true
  project(x, z, up = 0) {
    const v = new THREE.Vector3(x, this.heightAt(x, z) + up, -z).project(this.camera);
    const w = this.canvas.clientWidth, h = this.canvas.clientHeight;
    return v.z > 1 ? null : [(v.x + 1) / 2 * w, (1 - v.y) / 2 * h];
  }

  // draws one frame: camera behind the player at the current turn, pitch and zoom
  render(dt, tx, tz) {
    const ty = this.heightAt(tx, tz) + 1.1;
    this.target.set(tx, ty, -tz);
    const d = this.zoom, cp = Math.cos(this.pitch);
    this.camera.position.set(tx - Math.sin(this.yaw) * d * cp, ty + Math.sin(this.pitch) * d, -tz + Math.cos(this.yaw) * d * cp);
    this.camera.lookAt(this.target);
    for (const a of this.actors) {
      a.model.update(dt);
      if (a.held) { if (a.held.seq !== a.model.seq) { a.held.play(a.model.seq, { loop: a.model.loop, restart: true }); a.held.time = a.model.time; } a.held.update(dt); }
    }
    this.renderer.render(this.scene, this.camera);
  }
}
