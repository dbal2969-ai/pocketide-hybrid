// Tile map in the "pocketide map" format (made by game_assets: level from ASCII art, or import from Tiled).
//   { width, height, tileSize, layers: {name: [tile ids row by row, 0 = empty]}, collision: ['#..', ...], objects: [...], tilesets: [...] }
// Draws with a tileset picture when one is given, otherwise with a colour per tile id so a game is playable before any art exists.
// Part of PocketIDE's game kit; MIT licensed.
import { Grid } from './grid.js';

export class TileMap {
  constructor(json, image = null) {
    Object.assign(this, { width: json.width, height: json.height, tileSize: json.tileSize || 16, layers: json.layers || {}, collision: json.collision || [], objects: json.objects || [], tilesets: json.tilesets || [] });
    this.image = image;
    this.columns = (this.tilesets[0] && this.tilesets[0].columns) || (image ? Math.floor(image.width / this.tileSize) : 0);
    this.first = (this.tilesets[0] && this.tilesets[0].firstgid) || 1;
    this.grid = new Grid(this.width, this.height, (x, y) => this.solid(x, y));
    this.colours = {};
  }

  static async load(url, imageUrl = null) {
    const json = await (await fetch(url)).json();
    let image = null;
    if (imageUrl) image = await new Promise(res => { const i = new Image(); i.onload = () => res(i); i.onerror = () => res(null); i.src = imageUrl; });
    return new TileMap(json, image);
  }

  // rows: array of strings; legend: {char: {tile, solid, layer}}; also lists objects for chars in objectChars: {char: 'name'}
  static fromText(rows, legend, tileSize = 16, objectChars = {}) {
    const w = Math.max(...rows.map(r => r.length)), h = rows.length;
    const ground = new Array(w * h).fill(0), top = new Array(w * h).fill(0), collision = [], objects = [];
    rows.forEach((row, y) => {
      let line = '';
      for (let x = 0; x < w; x++) {
        const ch = row[x] === undefined ? ' ' : row[x], spec = legend[ch] || {};
        if (spec.layer === 'top') top[y * w + x] = spec.tile || 0; else ground[y * w + x] = spec.tile || 0;
        line += spec.solid ? '#' : '.';
        if (objectChars[ch]) objects.push({ name: objectChars[ch], type: objectChars[ch], x, y, w: 1, h: 1 });
      }
      collision.push(line);
    });
    const layers = { ground }; if (top.some(v => v)) layers.top = top;
    return new TileMap({ width: w, height: h, tileSize, layers, collision, objects });
  }

  solid(x, y) { return x < 0 || y < 0 || x >= this.width || y >= this.height || (this.collision[y] || '')[x] === '#'; }
  tile(layer, x, y) { const l = this.layers[layer]; return l && x >= 0 && y >= 0 && x < this.width && y < this.height ? l[y * this.width + x] : 0; }
  setSolid(x, y, on) { if (x < 0 || y < 0 || x >= this.width || y >= this.height) return; const r = this.collision[y]; this.collision[y] = r.slice(0, x) + (on ? '#' : '.') + r.slice(x + 1); }
  objectsOf(type) { return this.objects.filter(o => o.type === type || o.name === type); }

  colour(id) { return this.colours[id] || (this.colours[id] = 'hsl(' + ((id * 47) % 360) + ',45%,' + (38 + (id * 13) % 22) + '%)'); }

  // Draw the visible part. cam is a Camera (x, y = top-left in pixels, w, h = view size, zoom).
  draw(ctx, cam) {
    const ts = this.tileSize, z = cam.zoom || 1;
    const x0 = Math.max(0, Math.floor(cam.x / ts)), y0 = Math.max(0, Math.floor(cam.y / ts));
    const x1 = Math.min(this.width - 1, Math.floor((cam.x + cam.w / z) / ts)), y1 = Math.min(this.height - 1, Math.floor((cam.y + cam.h / z) / ts));
    for (const name of Object.keys(this.layers)) {
      const l = this.layers[name];
      for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
        const id = l[y * this.width + x]; if (!id) continue;
        if (this.image && this.columns) {
          const i = id - this.first;
          ctx.drawImage(this.image, (i % this.columns) * ts, Math.floor(i / this.columns) * ts, ts, ts, x * ts, y * ts, ts, ts);
        } else { ctx.fillStyle = this.colour(id); ctx.fillRect(x * ts, y * ts, ts, ts); }
      }
    }
  }
}
