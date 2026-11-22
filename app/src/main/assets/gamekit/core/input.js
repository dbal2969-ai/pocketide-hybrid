// Keyboard, touch joystick, on-screen buttons and taps in one place.
//   const input = new Input(canvas);
//   input.bind('jump', ['Space', 'ArrowUp', 'KeyW']);        // actions map to keys...
//   input.addButton('jump', 'A', (w, h) => [w - 70, h - 90], 40); // ...and to on-screen buttons of the same name
//   input.axis()      -> {x, y} from arrows/WASD or the joystick (left half of the screen), length at most 1
//   input.down('jump'), input.pressed('jump') (true for one frame), input.takeTaps() -> [{x, y}] canvas pixels
//   call input.endFrame() once per update and input.draw(ctx) after drawing the world.
// Part of PocketIDE's game kit; MIT licensed.
export class Input {
  constructor(canvas, { joystick = true } = {}) {
    this.canvas = canvas; this.joystick = joystick;
    this.keys = new Set(); this.edge = new Set(); this.binds = {}; this.buttons = [];
    this.stick = { id: -1, cx: 0, cy: 0, x: 0, y: 0 }; this.taps = []; this.pointer = { x: 0, y: 0, down: false };
    this.touched = false;
    addEventListener('keydown', e => { if (!this.keys.has(e.code)) this.edge.add(e.code); this.keys.add(e.code); if (e.code.startsWith('Arrow') || e.code === 'Space') e.preventDefault(); });
    addEventListener('keyup', e => this.keys.delete(e.code));
    addEventListener('blur', () => this.keys.clear());
    canvas.style.touchAction = 'none';
    canvas.addEventListener('pointerdown', e => this.down_(e));
    canvas.addEventListener('pointermove', e => this.move_(e));
    canvas.addEventListener('pointerup', e => this.up_(e));
    canvas.addEventListener('pointercancel', e => this.up_(e));
  }

  bind(action, codes) { this.binds[action] = codes; }
  addButton(action, label, pos, r = 36) { this.buttons.push({ action, label, pos, r, id: -1, held: false, hit: false }); }

  pos_(e) { const r = this.canvas.getBoundingClientRect(); return [(e.clientX - r.left) * this.canvas.width / r.width, (e.clientY - r.top) * this.canvas.height / r.height]; }

  down_(e) {
    const [x, y] = this.pos_(e);
    if (e.pointerType !== 'mouse') this.touched = true;
    this.pointer = { x, y, down: true };
    for (const b of this.buttons) {
      const [bx, by] = b.pos(this.canvas.width, this.canvas.height);
      if (Math.hypot(x - bx, y - by) <= b.r * 1.3) { b.id = e.pointerId; b.held = true; b.hit = true; this.canvas.setPointerCapture(e.pointerId); return; }
    }
    if (this.joystick && e.pointerType !== 'mouse' && x < this.canvas.width * 0.5 && this.stick.id < 0) {
      this.stick = { id: e.pointerId, cx: x, cy: y, x: 0, y: 0 }; this.canvas.setPointerCapture(e.pointerId); this.tapCandidate = null; return;
    }
    this.taps.push({ x, y });
  }

  move_(e) {
    const [x, y] = this.pos_(e);
    this.pointer.x = x; this.pointer.y = y;
    if (e.pointerId === this.stick.id) {
      const dx = x - this.stick.cx, dy = y - this.stick.cy, len = Math.hypot(dx, dy), max = 50;
      this.stick.x = len > 0 ? dx / Math.max(len, max) : 0; this.stick.y = len > 0 ? dy / Math.max(len, max) : 0;
    }
  }

  up_(e) {
    this.pointer.down = false;
    if (e.pointerId === this.stick.id) this.stick = { id: -1, cx: 0, cy: 0, x: 0, y: 0 };
    for (const b of this.buttons) if (b.id === e.pointerId) { b.id = -1; b.held = false; }
  }

  axis() {
    let x = 0, y = 0;
    const k = c => this.keys.has(c);
    if (k('ArrowLeft') || k('KeyA')) x -= 1; if (k('ArrowRight') || k('KeyD')) x += 1;
    if (k('ArrowUp') || k('KeyW')) y -= 1; if (k('ArrowDown') || k('KeyS')) y += 1;
    if (this.stick.id >= 0) { x = this.stick.x; y = this.stick.y; }
    const len = Math.hypot(x, y); if (len > 1) { x /= len; y /= len; }
    return { x, y };
  }

  down(action) {
    if ((this.binds[action] || []).some(c => this.keys.has(c))) return true;
    return this.buttons.some(b => b.action === action && b.held);
  }

  pressed(action) {
    if ((this.binds[action] || []).some(c => this.edge.has(c))) return true;
    return this.buttons.some(b => b.action === action && b.hit);
  }

  takeTaps() { const t = this.taps; this.taps = []; return t; }

  endFrame() { this.edge.clear(); for (const b of this.buttons) b.hit = false; }

  draw(ctx) {
    ctx.save();
    if (this.stick.id >= 0) {
      ctx.globalAlpha = 0.35; ctx.fillStyle = '#fff';
      ctx.beginPath(); ctx.arc(this.stick.cx, this.stick.cy, 50, 0, 7); ctx.fill();
      ctx.globalAlpha = 0.7; ctx.beginPath(); ctx.arc(this.stick.cx + this.stick.x * 50, this.stick.cy + this.stick.y * 50, 22, 0, 7); ctx.fill();
    }
    for (const b of this.buttons) {
      const [x, y] = b.pos(this.canvas.width, this.canvas.height);
      ctx.globalAlpha = b.held ? 0.7 : 0.4; ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.arc(x, y, b.r, 0, 7); ctx.fill();
      ctx.globalAlpha = 0.9; ctx.fillStyle = '#000'; ctx.font = 'bold ' + Math.round(b.r * 0.7) + 'px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillText(b.label, x, y);
    }
    ctx.restore();
  }
}
