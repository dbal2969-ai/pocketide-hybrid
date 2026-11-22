// 2D camera: follow a target smoothly, stay inside the world, shake. x, y are the top-left of the view in world pixels.
// Part of PocketIDE's game kit; MIT licensed.
export class Camera {
  constructor(w, h) { this.x = 0; this.y = 0; this.w = w; this.h = h; this.zoom = 1; this.shakeTime = 0; this.shakeAmount = 0; this.bounds = null; }

  resize(w, h) { this.w = w; this.h = h; }
  setBounds(width, height) { this.bounds = { w: width, h: height }; }
  shake(amount = 6, seconds = 0.25) { this.shakeAmount = amount; this.shakeTime = seconds; }

  // Move toward centring (tx, ty); smoothing is how fast (higher = snappier, 0 = jump).
  follow(tx, ty, dt, smoothing = 8) {
    const gx = tx - this.w / this.zoom / 2, gy = ty - this.h / this.zoom / 2;
    const k = smoothing > 0 ? 1 - Math.exp(-smoothing * dt) : 1;
    this.x += (gx - this.x) * k; this.y += (gy - this.y) * k;
    if (this.bounds) {
      const vw = this.w / this.zoom, vh = this.h / this.zoom;
      this.x = vw >= this.bounds.w ? (this.bounds.w - vw) / 2 : Math.max(0, Math.min(this.x, this.bounds.w - vw));
      this.y = vh >= this.bounds.h ? (this.bounds.h - vh) / 2 : Math.max(0, Math.min(this.y, this.bounds.h - vh));
    }
    if (this.shakeTime > 0) this.shakeTime -= dt;
  }

  begin(ctx) {
    ctx.save(); ctx.scale(this.zoom, this.zoom);
    const s = this.shakeTime > 0 ? this.shakeAmount : 0;
    ctx.translate(-Math.round(this.x + (Math.random() - 0.5) * s), -Math.round(this.y + (Math.random() - 0.5) * s));
  }
  end(ctx) { ctx.restore(); }

  toWorld(sx, sy) { return { x: sx / this.zoom + this.x, y: sy / this.zoom + this.y }; }
  toScreen(wx, wy) { return { x: (wx - this.x) * this.zoom, y: (wy - this.y) * this.zoom }; }
}
