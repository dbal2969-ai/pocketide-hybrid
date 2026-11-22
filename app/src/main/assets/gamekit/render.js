// Top-down map renderer for World squares exported by game_cache. Draws ground colours from floors.json, walls and objects.
// Written for PocketIDE's game kit; MIT licensed.
import { N, E, S, W, FULL } from './world.js';

const PRE = 12; // pixels per tile in the pre-rendered square pictures

function colourOf(floors, json, i) {
  if (floors) {
    const o = json.overlay ? json.overlay[i] : 0;
    if (o && floors.overlays[o - 1]) return floors.overlays[o - 1];      // map tiles store the definition id + 1; 0 means none
    const u = json.underlay ? json.underlay[i] : 0;
    if (u && floors.underlays[u - 1]) return floors.underlays[u - 1];
  }
  return null;
}


// Object pictures from `game_cache action=place_objects`: where each building or object stands (data/places/m<x>_<z>.json,
// rows [id, x, z, plane, width, length, underRoof]) and a picture per object id (data/objects/<id>.png).
// Returns { places: Map "x,z" -> rows, sprites: Map id -> Image }. A project without them just keeps the coloured blocks.
export async function loadPlaces(world, dir = 'data') {
  const places = new Map(), sprites = new Map(), ids = new Set();
  await Promise.all([...world.squares.values()].map(async sq => {
    const k = sq.json.x + ',' + sq.json.z;
    try {
      const r = await fetch(dir + '/places/m' + sq.json.x + '_' + sq.json.z + '.json');
      if (!r.ok) return;
      const rows = (await r.json()).objects || [];
      places.set(k, new Map(rows.map(o => [o[0] + ',' + o[1] + ',' + o[2] + ',' + o[3], o])));
      for (const o of rows) ids.add(o[0]);
    } catch (e) { /* no places for this square */ }
  }));
  await Promise.all([...ids].map(id => new Promise(res => {
    const img = new Image();
    img.onload = () => { sprites.set(id, img); res(); };
    img.onerror = () => res();
    img.src = dir + '/objects/' + id + '.png';
  })));
  return { places, sprites };
}

// names: optional {id: name} (from objects.json) so trees, doors and so on get their own look.
// Shaded ground pictures from `game_cache export kind=ground` (data/ground/g<x>_<z>.png), loaded when they exist.
// Returns a Map "x,z" -> Image for the squares that have one; a project without them just keeps the flat colours.
export async function loadGround(world, dir = 'data/ground') {
  const out = new Map();
  await Promise.all([...world.squares.values()].map(sq => new Promise(res => {
    const img = new Image();
    img.onload = () => { out.set(sq.json.x + ',' + sq.json.z, img); res(); };
    img.onerror = () => res();
    img.src = dir + '/g' + sq.json.x + '_' + sq.json.z + '.png';
  })));
  return out;
}

export function renderSquare(sq, floors, names, plane = 0, ground = null, placed = null) {
  const json = sq.json, f = sq.planes[plane];
  const c = document.createElement('canvas'); c.width = c.height = 64 * PRE;
  const g = c.getContext('2d');
  g.fillStyle = '#1a1a1a'; g.fillRect(0, 0, c.width, c.height);
  for (let x = 0; x < 64; x++) for (let z = 0; z < 64; z++) {
    const i = x * 64 + z, px = x * PRE, py = (63 - z) * PRE;
    const col = plane === 0 ? colourOf(floors, json, i) : null;
    g.fillStyle = col || (f[i] & FULL ? '#444' : '#6a8f4f');
    g.fillRect(px, py, PRE, PRE);
  }
  if (ground && plane === 0) { g.imageSmoothingEnabled = true; g.drawImage(ground, 0, 0, c.width, c.height); }  // lit ground over the flat colours
  // objects
  if (json.objects) for (const o of json.objects) {
    if (o[3] !== plane) continue;
    if (placed && placed.hidden && placed.hidden.has(o.slice(0, 4).join(','))) continue;   // a felled tree is not drawn
    const name = (names && names[o[0]]) || '';
    const px = o[1] * PRE, py = (63 - o[2]) * PRE;
    // a real picture of the object, when place_objects made one: under-roof pictures are hidden while the player stands under a roof
    const row = placed && placed.rows ? placed.rows.get(o[0] + ',' + o[1] + ',' + o[2] + ',' + o[3]) : null;
    const img = row && placed.sprites ? placed.sprites.get(o[0]) : null;
    if (img) {
      if (row[6] && placed.roofOver) continue;
      const w = row[4], len = row[5], size = Math.max(w, len) * PRE * 2;
      const cx = px + (w * PRE) / 2, cy = py + PRE - size / 2, turn = (row[7] || 0) * Math.PI / 2;
      g.save(); g.translate(cx, cy); g.rotate(-turn); g.drawImage(img, -size / 2, -size / 2, size, size); g.restore();
      continue;
    }
    const type = o[4];
    if (type === 22 || type === 9) continue;
    if (type <= 3) continue; // walls are drawn from the flags below
    if (type >= 4 && type <= 8) continue;
    const tree = /tree|oak|willow|yew|maple|palm/i.test(name), door = /door|gate/i.test(name);
    g.fillStyle = tree ? '#2f6b2a' : door ? '#d8b04a' : /water|fountain|well/i.test(name) ? '#4a7bd0' : '#8a6a4a';
    if (tree) { g.beginPath(); g.arc(px + PRE / 2, py + PRE / 2, PRE * 0.8, 0, 7); g.fill(); }
    else g.fillRect(px + 1, py + 1, PRE - 2, PRE - 2);
  }
  // walls and fully blocked tiles from the collision flags
  g.strokeStyle = '#111'; g.lineWidth = 2;
  g.beginPath();
  for (let x = 0; x < 64; x++) for (let z = 0; z < 64; z++) {
    const v = f[x * 64 + z], px = x * PRE, py = (63 - z) * PRE;
    if (v & FULL && !json.objects) { g.fillStyle = 'rgba(0,0,0,0.35)'; g.fillRect(px, py, PRE, PRE); }
    if (v & N) { g.moveTo(px, py); g.lineTo(px + PRE, py); }
    if (v & S) { g.moveTo(px, py + PRE); g.lineTo(px + PRE, py + PRE); }
    if (v & W) { g.moveTo(px, py); g.lineTo(px, py + PRE); }
    if (v & E) { g.moveTo(px + PRE, py); g.lineTo(px + PRE, py + PRE); }
  }
  g.stroke();
  return c;
}

export class MapView {
  constructor(canvas, world, floors, names, ground = null) {
    this.canvas = canvas; this.world = world; this.floors = floors; this.names = names; this.ground = ground;
    this.cache = new Map(); this.tile = 20; this.cx = 3222; this.cz = 3218; this.plane = 0;
    this.places = new Map(); this.sprites = new Map(); this.roofOver = false;   // object pictures and whether the player is under a roof
    this.hidden = new Map(); this.down = new Set();   // felled trees: square -> keys of objects not drawn, and the nodes to draw a stump for
  }
  invalidate() { this.cache.clear(); }
  invalidateSquare(rx, rz) { for (const k of [...this.cache.keys()]) if (k.startsWith(rx + ',' + rz + ',')) this.cache.delete(k); }
  // hide or show an object found by gather.js (indexNodes); a hidden tree leaves a stump
  hide(n) { const k = n.rx + ',' + n.rz; if (!this.hidden.has(k)) this.hidden.set(k, new Set()); this.hidden.get(k).add(n.lkey); this.down.add(n); this.invalidateSquare(n.rx, n.rz); }
  show(n) { const s = this.hidden.get(n.rx + ',' + n.rz); if (s) s.delete(n.lkey); this.down.delete(n); this.invalidateSquare(n.rx, n.rz); }
  picture(rx, rz) {
    const k = rx + ',' + rz + ',' + this.plane + ',' + this.roofOver;
    if (!this.cache.has(k)) {
      const sq = this.world.squares.get(rx + ',' + rz);
      const placed = { rows: this.places.get(rx + ',' + rz), sprites: this.sprites, roofOver: this.roofOver, hidden: this.hidden.get(rx + ',' + rz) };
      this.cache.set(k, sq ? renderSquare(sq, this.floors, this.names, this.plane, this.ground ? this.ground.get(rx + ',' + rz) : null, placed) : null);
    }
    return this.cache.get(k);
  }
  // screen position of a world tile's top-left corner
  toScreen(x, z) { return [Math.round(this.canvas.width / 2 + (x - this.cx) * this.tile), Math.round(this.canvas.height / 2 - (z + 1 - this.cz) * this.tile)]; }
  // world tile under a screen point
  toTile(sx, sy) { return [Math.floor((sx - this.canvas.width / 2) / this.tile + this.cx), Math.floor((this.canvas.height / 2 - sy) / this.tile + this.cz)]; }
  draw(extra) {
    const g = this.canvas.getContext('2d'), t = this.tile;
    g.imageSmoothingEnabled = false;
    g.fillStyle = '#000'; g.fillRect(0, 0, this.canvas.width, this.canvas.height);
    const [x0, z1] = this.toTile(0, 0), [x1, z0] = this.toTile(this.canvas.width, this.canvas.height);
    for (let rx = x0 >> 6; rx <= x1 >> 6; rx++) for (let rz = z0 >> 6; rz <= z1 >> 6; rz++) {
      const pic = this.picture(rx, rz); if (!pic) continue;
      const [sx, sy] = this.toScreen(rx * 64, rz * 64 + 63);
      g.drawImage(pic, sx, sy, 64 * t, 64 * t);
    }
    for (const n of this.down) {   // a stump where a tree was cut down
      const [sx, sy] = this.toScreen(n.x, n.z + n.l - 1), cx = sx + n.w * t / 2, cy = sy + n.l * t / 2;
      g.fillStyle = '#5a3d1e'; g.strokeStyle = '#2a1a08'; g.lineWidth = 2;
      g.beginPath(); g.ellipse(cx, cy, t * 0.4, t * 0.3, 0, 0, 7); g.fill(); g.stroke();
      g.fillStyle = '#8a6a3a'; g.beginPath(); g.ellipse(cx, cy - t * 0.05, t * 0.26, t * 0.18, 0, 0, 7); g.fill();
    }
    if (extra) extra(g, this);
  }
}

// Minimap: the same squares drawn small around the player, with a dot for the player and one for the view centre.
// `canvas` is a small element in the corner; `view` is the MapView, `me` the player (tile x, z).
export function drawMinimap(canvas, view, me, tiles = 48) {
  const g = canvas.getContext('2d'), s = canvas.width / tiles;
  g.imageSmoothingEnabled = false;
  g.fillStyle = '#000'; g.fillRect(0, 0, canvas.width, canvas.height);
  const x0 = me.x - tiles / 2, z0 = me.z - tiles / 2;
  for (let rx = x0 >> 6; rx <= (x0 + tiles) >> 6; rx++) for (let rz = z0 >> 6; rz <= (z0 + tiles) >> 6; rz++) {
    const pic = view.picture(rx, rz); if (!pic) continue;
    // the square's top-left tile, drawn from its picture (PRE pixels per tile, north at the top)
    const sx = (rx * 64 - x0) * s, sy = (z0 + tiles - (rz * 64 + 64)) * s;
    g.drawImage(pic, sx, sy, 64 * s, 64 * s);
  }
  g.fillStyle = '#fff'; g.beginPath(); g.arc(canvas.width / 2, canvas.height / 2, Math.max(2, s * 0.6), 0, 7); g.fill();
  g.strokeStyle = '#ff0'; g.lineWidth = 1; g.strokeRect(0.5, 0.5, canvas.width - 1, canvas.height - 1);
}
